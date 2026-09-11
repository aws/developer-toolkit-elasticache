// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import { rmSync } from "node:fs";

for (const directory of ["dist", "dist-test"]) {
  rmSync(directory, { force: true, recursive: true });
}
