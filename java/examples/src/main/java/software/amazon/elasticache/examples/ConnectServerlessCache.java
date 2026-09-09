/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.examples;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import software.amazon.awssdk.regions.Region;
import software.amazon.elasticache.auth.ElastiCacheIamAuthTokenProvider;

/**
 * Connects to an ElastiCache serverless cache over TLS using IAM authentication.
 *
 * <p>This example uses the Redis serialization protocol directly so the toolkit's example
 * does not require or endorse a particular Redis or Valkey client. Applications should
 * normally use their preferred client library and supply {@link
 * ElastiCacheIamAuthTokenProvider#getUserId()} and {@link
 * ElastiCacheIamAuthTokenProvider#getToken()} when authenticating or reconnecting.
 */
public final class ConnectServerlessCache {
    private static final int DEFAULT_PORT = 6379;

    private ConnectServerlessCache() {}

    /**
     * Runs the connection example.
     *
     * @param args cache name, IAM user ID, endpoint, region, and optional port
     * @throws IOException if the TLS connection or Redis protocol exchange fails
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 4 || args.length > 5) {
            throw new IllegalArgumentException(
                    "Usage: ConnectServerlessCache "
                            + "<cache-name> <user-id> <endpoint> <region> [port]");
        }

        String cacheName = args[0];
        String userId = args[1];
        String endpoint = args[2];
        Region region = Region.of(args[3]);
        int port = args.length == 5 ? Integer.parseInt(args[4]) : DEFAULT_PORT;

        ElastiCacheIamAuthTokenProvider auth = ElastiCacheIamAuthTokenProvider.builder()
                .serverlessCacheName(cacheName)
                .userId(userId)
                .region(region)
                .build();

        SSLSocketFactory socketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) socketFactory.createSocket(endpoint, port);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                OutputStream output = socket.getOutputStream()) {
            enableHostnameVerification(socket);
            socket.startHandshake();

            writeCommand(output, "AUTH", auth.getUserId(), auth.getToken());
            expectSimpleResponse(input, "OK");

            writeCommand(output, "PING");
            expectSimpleResponse(input, "PONG");
        }

        System.out.println("IAM authentication succeeded; PING returned PONG.");
    }

    static void enableHostnameVerification(SSLSocket socket) {
        SSLParameters sslParameters = socket.getSSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(sslParameters);
    }

    private static void writeCommand(OutputStream output, String... arguments) throws IOException {
        writeAscii(output, "*" + arguments.length + "\r\n");
        for (String argument : arguments) {
            byte[] value = argument.getBytes(StandardCharsets.UTF_8);
            writeAscii(output, "$" + value.length + "\r\n");
            output.write(value);
            writeAscii(output, "\r\n");
        }
        output.flush();
    }

    private static void expectSimpleResponse(BufferedInputStream input, String expected)
            throws IOException {
        int responseType = input.read();
        if (responseType == -1) {
            throw new IOException("ElastiCache closed the connection without a response");
        }

        String response = readLine(input);
        if (responseType == '-') {
            throw new IOException("ElastiCache returned an error: " + response);
        }
        if (responseType != '+' || !expected.equals(response)) {
            throw new IOException("Unexpected ElastiCache response");
        }
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current == -1) {
                throw new IOException("ElastiCache closed the connection mid-response");
            }
            if (previous == '\r' && current == '\n') {
                line.setLength(line.length() - 1);
                return line.toString();
            }
            line.append((char) current);
            previous = current;
        }
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }
}
