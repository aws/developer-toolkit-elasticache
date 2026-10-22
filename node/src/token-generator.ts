// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import { fromNodeProviderChain } from "@aws-sdk/credential-providers";
import { loadConfig, NODE_REGION_CONFIG_OPTIONS } from "@smithy/core/config";
import { HttpRequest } from "@smithy/core/protocols";
import { Hash } from "@smithy/hash-node";
import { SignatureV4 } from "@smithy/signature-v4";
import type {
  AwsCredentialIdentity,
  AwsCredentialIdentityProvider,
  QueryParameterBag,
} from "@smithy/types";

import { ConfigurationError, InvalidParameterError } from "./errors.js";

/** Lifetime of a presigned ElastiCache IAM token, in seconds. */
export const TOKEN_TTL_SECONDS = 900;
const SIGNING_SERVICE = "elasticache";

// The cache name becomes the SigV4 signing host. Keep this validation tight so a
// malformed or hostile value cannot be signed as a different host.
const CACHE_NAME_PATTERN = /^[a-zA-Z][a-zA-Z0-9]*(-[a-zA-Z0-9]+)*$/;

// ElastiCache service-managed user ids may use the special "default." prefix,
// for example "default.iam-user".
const USER_ID_PATTERN = /^(?:default\.)?[a-zA-Z][a-zA-Z0-9-]*$/;

const NO_REGION_MESSAGE =
  "No AWS region found. Pass region explicitly, or configure one via " +
  "AWS_REGION, AWS_DEFAULT_REGION, or the region setting in your AWS config profile.";
const NO_CREDENTIALS_MESSAGE =
  "No AWS credentials found. Configure credentials via the environment, " +
  "shared config/credentials files, or an instance/container role.";

export interface TokenGeneratorOptions {
  /** ElastiCache user id used in the signed `User` query parameter. */
  userId: string;
  /** AWS region. Resolution falls back to the supported AWS configuration sources. */
  region?: string;
  /** Name of a serverless cache. Exactly one target option is required. */
  serverlessCacheName?: string;
  /** Id of a node-based replication group. Exactly one target option is required. */
  replicationGroupId?: string;
}

/**
 * Advanced dependencies for deterministic tests and applications with an
 * explicitly managed AWS configuration. These are intentionally optional; the
 * normal API uses the standard Node.js AWS credential and region providers.
 */
export interface TokenGeneratorDependencies {
  credentialProvider?: AwsCredentialIdentityProvider;
  regionProvider?: () => Promise<string | undefined>;
  signingDate?: Date;
}

type Target = {
  cacheName: string;
  serverless: boolean;
};

type RegionResolution = { region: string } | { error: ConfigurationError };

function validateAndNormalize(
  parameter: string,
  value: unknown,
  pattern: RegExp,
): string {
  if (typeof value !== "string") {
    throw new InvalidParameterError(
      `Invalid value for parameter '${parameter}': must match ${pattern.source}`,
    );
  }

  // ElastiCache stores cache names and user ids lowercase server-side. Normalize
  // before validation and signing so the signed value matches the value sent by
  // the client; otherwise authentication can fail with WRONGPASS.
  const normalized = value.toLowerCase();
  if (!pattern.test(normalized)) {
    // Do not include the supplied value: it may contain newlines or URL syntax.
    throw new InvalidParameterError(
      `Invalid value for parameter '${parameter}': must match ${pattern.source}`,
    );
  }
  return normalized;
}

function resolveTarget(options: TokenGeneratorOptions): Target {
  const { serverlessCacheName, replicationGroupId } = options;

  // Validate inside each branch so an invalid value is reported under the public
  // parameter name the caller actually supplied.
  if (serverlessCacheName && !replicationGroupId) {
    return {
      cacheName: validateAndNormalize(
        "serverlessCacheName",
        serverlessCacheName,
        CACHE_NAME_PATTERN,
      ),
      serverless: true,
    };
  }

  if (replicationGroupId && !serverlessCacheName) {
    return {
      cacheName: validateAndNormalize(
        "replicationGroupId",
        replicationGroupId,
        CACHE_NAME_PATTERN,
      ),
      serverless: false,
    };
  }

  throw new InvalidParameterError(
    "Invalid parameter combination for 'serverlessCacheName' and " +
      "'replicationGroupId': exactly one must be provided.",
  );
}

async function resolveRegion(
  region: string | undefined,
  regionProvider: (() => Promise<string | undefined>) | undefined,
): Promise<string> {
  const fromArgument = region || undefined;
  const fromEnvironment = process.env.AWS_REGION || process.env.AWS_DEFAULT_REGION;
  if (fromArgument || fromEnvironment) {
    return fromArgument || fromEnvironment!;
  }

  // Environment variables were checked above; now fall back to the shared
  // AWS configuration while preserving the documented precedence.
  try {
    const resolved = await (regionProvider ?? loadSharedRegion)();
    if (resolved) {
      return resolved;
    }
  } catch {
    // Present one stable, actionable configuration error instead of leaking
    // provider internals or a file path from the local AWS configuration.
  }

  throw new ConfigurationError(NO_REGION_MESSAGE);
}

function retainRegion(
  region: string | undefined,
  regionProvider: (() => Promise<string | undefined>) | undefined,
): Promise<RegionResolution> {
  // Keep this promise fulfilled even when configuration is invalid. A provider
  // may be constructed without an immediate async error observer; getToken()
  // reports the retained configuration error when the token is requested.
  return resolveRegion(region, regionProvider).then(
    (resolvedRegion) => ({ region: resolvedRegion }),
    (error: unknown) => ({
      error:
        error instanceof ConfigurationError
          ? error
          : new ConfigurationError(NO_REGION_MESSAGE),
    }),
  );
}

async function loadSharedRegion(): Promise<string | undefined> {
  // Override the SDK selector/default so all "no region" cases return undefined
  // here and are converted by resolveRegion into one stable ConfigurationError,
  // rather than exposing the SDK's provider-specific error shape.
  const regionConfig = {
    ...NODE_REGION_CONFIG_OPTIONS,
    environmentVariableSelector: (env: Record<string, string | undefined>) =>
      env.AWS_REGION || env.AWS_DEFAULT_REGION,
    default: async () => undefined,
  };
  return loadConfig<string | undefined>(regionConfig)();
}

async function resolveCredentials(
  credentialProvider: AwsCredentialIdentityProvider,
): Promise<AwsCredentialIdentity> {
  try {
    // Resolve on every request instead of caching: SSO, assume-role, container,
    // and instance-metadata providers may rotate or refresh their credentials.
    const credentials = await credentialProvider();
    if (!credentials?.accessKeyId || !credentials.secretAccessKey) {
      throw new Error("The credential provider returned no usable credentials.");
    }
    return credentials;
  } catch {
    throw new ConfigurationError(NO_CREDENTIALS_MESSAGE);
  }
}

function encodeQueryComponent(value: string): string {
  // encodeURIComponent leaves !'()* unescaped, but SigV4 requires them to be
  // percent-encoded. Returning a different encoding from the one that was signed
  // makes the service canonicalize a different query and silently reject the token.
  return encodeURIComponent(value).replace(
    /[!'()*]/g,
    (character) => `%${character.charCodeAt(0).toString(16).toUpperCase()}`,
  );
}

function formatQuery(query: QueryParameterBag): string {
  // Preserve repeated query values while applying the SigV4-compatible encoding
  // above to every key and value in the final token.
  const entries: string[] = [];
  for (const [key, value] of Object.entries(query)) {
    if (value === null) {
      entries.push(`${encodeQueryComponent(key)}=`);
      continue;
    }
    const values = Array.isArray(value) ? value : [value];
    for (const item of values) {
      entries.push(`${encodeQueryComponent(key)}=${encodeQueryComponent(item)}`);
    }
  }
  return entries.join("&");
}

/**
 * Sign the ElastiCache IAM authentication request.
 *
 * `target.cacheName` is deliberately used as the hostname and `Host` header: it
 * is the SigV4 signing host expected by ElastiCache, not the connection endpoint
 * DNS name. For serverless caches, `ResourceType` is part of the canonical query,
 * so it must be added before presigning rather than appended afterward.
 */
async function signToken(
  target: Target,
  userId: string,
  region: string,
  credentials: AwsCredentialIdentity,
  signingDate: Date | undefined,
): Promise<string> {
  const query: QueryParameterBag = {
    Action: "connect",
    User: userId,
  };
  if (target.serverless) {
    // This parameter changes the canonical request and therefore the signature;
    // node-based replication groups must omit it.
    query.ResourceType = "ServerlessCache";
  }

  const request = new HttpRequest({
    protocol: "https:",
    hostname: target.cacheName,
    method: "GET",
    path: "/",
    headers: { host: target.cacheName },
    query,
  });
  const signer = new SignatureV4({
    credentials,
    region,
    service: SIGNING_SERVICE,
    sha256: Hash.bind(null, "sha256"),
  });
  const signed = await signer.presign(request, {
    expiresIn: TOKEN_TTL_SECONDS,
    signingDate,
  });

  return `${signed.hostname}/?${formatQuery(signed.query ?? {})}`;
}

function defaultCredentialProvider(): AwsCredentialIdentityProvider {
  return fromNodeProviderChain();
}

/**
 * Generate an ElastiCache IAM authentication token.
 *
 * Exactly one of `serverlessCacheName` or `replicationGroupId` must be provided.
 * The selected value is used as the SigV4 signing host; it is not the connection
 * endpoint DNS name. The `userId` is normalized to the lowercase value used in the
 * signed `User` query parameter.
 *
 * When `region` is omitted, resolution checks `AWS_REGION`, `AWS_DEFAULT_REGION`,
 * and the standard AWS shared configuration. Credentials use the standard Node.js
 * provider chain and are resolved for each invocation so refreshed credentials are
 * picked up.
 *
 * @param options Target, user, and optional region used to create the token.
 * @param dependencies Optional providers and signing time for controlled callers
 * and deterministic tests.
 * @returns The presigned ElastiCache IAM authentication token.
 */
export async function generateIamAuthToken(
  options: TokenGeneratorOptions,
  dependencies: TokenGeneratorDependencies = {},
): Promise<string> {
  const target = resolveTarget(options);
  const userId = validateAndNormalize("userId", options.userId, USER_ID_PATTERN);
  const region = await resolveRegion(options.region, dependencies.regionProvider);
  const credentials = await resolveCredentials(
    dependencies.credentialProvider ?? defaultCredentialProvider(),
  );

  return signToken(target, userId, region, credentials, dependencies.signingDate);
}

/**
 * Client-agnostic ElastiCache IAM token provider.
 *
 * The target and normalized user id are retained, and the region is resolved once
 * during construction. `getToken()` signs a fresh token and re-fetches credentials
 * on every call so rotated SSO, assume-role, container, or instance credentials are
 * used without changing the signing region.
 *
 * Region resolution is asynchronous, so a constructor cannot report a missing region.
 * Prefer `ElastiCacheIAMAuthTokenProvider.create()`, which awaits that resolution and
 * fails immediately; the constructor retains the error and reports it from the first
 * `getToken()` call instead.
 */
export class ElastiCacheIAMAuthTokenProvider {
  private readonly target: Target;
  private readonly userIdValue: string;
  private readonly credentialProvider: AwsCredentialIdentityProvider;
  private readonly regionResolution: Promise<RegionResolution>;
  private readonly signingDate?: Date;

  constructor(
    options: TokenGeneratorOptions,
    dependencies: TokenGeneratorDependencies = {},
  ) {
    this.target = resolveTarget(options);
    this.userIdValue = validateAndNormalize("userId", options.userId, USER_ID_PATTERN);
    this.credentialProvider =
      dependencies.credentialProvider ?? defaultCredentialProvider();
    this.regionResolution = retainRegion(options.region, dependencies.regionProvider);
    this.signingDate = dependencies.signingDate;
  }

  /**
   * Create a provider, resolving the region before returning.
   *
   * Use this instead of `new` to surface a missing or unreadable region
   * configuration up front rather than at the first connection attempt.
   *
   * @throws {InvalidParameterError} for an invalid target or user id.
   * @throws {ConfigurationError} when no region can be resolved.
   */
  static async create(
    options: TokenGeneratorOptions,
    dependencies: TokenGeneratorDependencies = {},
  ): Promise<ElastiCacheIAMAuthTokenProvider> {
    const provider = new ElastiCacheIAMAuthTokenProvider(options, dependencies);
    await provider.ensureRegionResolved();
    return provider;
  }

  /**
   * Await region resolution, throwing the retained configuration error if the
   * region could not be resolved.
   *
   * @throws {ConfigurationError} when no region can be resolved.
   */
  async ensureRegionResolved(): Promise<void> {
    const regionResolution = await this.regionResolution;
    if ("error" in regionResolution) {
      throw regionResolution.error;
    }
  }

  get userId(): string {
    return this.userIdValue;
  }

  /** Sign and return a fresh token using the provider's retained target and region. */
  async getToken(): Promise<string> {
    const regionResolution = await this.regionResolution;
    if ("error" in regionResolution) {
      throw regionResolution.error;
    }
    const credentials = await resolveCredentials(this.credentialProvider);
    return signToken(
      this.target,
      this.userIdValue,
      regionResolution.region,
      credentials,
      this.signingDate,
    );
  }
}
