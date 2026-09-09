/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.elasticache.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.junit.jupiter.api.Test;

class ConnectServerlessCacheTest {
    @Test
    void enablesHostnameVerification() throws IOException {
        SSLSocketFactory socketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (SSLSocket socket = (SSLSocket) socketFactory.createSocket()) {
            ConnectServerlessCache.enableHostnameVerification(socket);

            assertEquals(
                    "HTTPS",
                    socket.getSSLParameters().getEndpointIdentificationAlgorithm());
        }
    }
}
