/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.it.utils;

import static org.keycloak.quarkus.runtime.Environment.LAUNCH_MODE;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.apache.commons.io.FileUtils;
import org.eclipse.aether.artifact.Artifact;
import org.jboss.logging.Logger;

import io.quarkus.bootstrap.util.ZipUtils;

public final class KeycloakDistribution {

    private static final Logger LOGGER = Logger.getLogger(KeycloakDistribution.class);

    private Process keycloak;
    private int exitCode = -1;
    private final Path distPath;
    private final List<String> outputStream = new ArrayList<>();
    private final List<String> errorStream = new ArrayList<>();
    private boolean reCreate;
    private boolean manualStop;
    private String relativePath;
    private int httpPort;
    private boolean debug;
    private ExecutorService outputExecutor;

    public <T> KeycloakDistribution() {
        distPath = prepareDistribution();
    }

    public void start(List<String> arguments) {
        reset();
        if (manualStop && isRunning()) {
            throw new IllegalStateException("Server already running. You should manually stop the server before starting it again.");
        }
        stopIfRunning();
        try {
            startServer(arguments);
            if (manualStop) {
                asyncReadOutput();
                waitForReadiness();
            } else {
                readOutput();
            }
        } catch (Exception cause) {
            stopIfRunning();
            throw new RuntimeException("Failed to start the server", cause);
        } finally {
            if (!manualStop) {
                stopIfRunning();
            }
        }
    }

    public void stopIfRunning() {
        if (isRunning()) {
            try {
                keycloak.destroy();
                keycloak.waitFor(10, TimeUnit.SECONDS);
                exitCode = keycloak.exitValue();
            } catch (Exception cause) {
                keycloak.destroyForcibly();
                throw new RuntimeException("Failed to stop the server", cause);
            }
        }

        shutdownOutputExecutor();
    }

    public List<String> getOutputStream() {
        return outputStream;
    }

    public List<String> getErrorStream() {
        return errorStream;
    }

    public int getExitCode() {
        return exitCode;
    }

    public void setReCreate(boolean reCreate) {
        this.reCreate = reCreate;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }

    public void setManualStop(boolean manualStop) {
        this.manualStop = manualStop;
    }

    private String[] getCliArgs(List<String> arguments) {
        List<String> commands = new ArrayList<>();

        commands.add("./kc.sh");

        if (debug) {
            commands.add("--debug");
        }

        if (!manualStop) {
            commands.add("-D" + LAUNCH_MODE + "=test");
        }

        this.relativePath = arguments.stream().filter(arg -> arg.startsWith("--http-relative-path")).map(arg -> arg.substring(arg.indexOf('=') + 1)).findAny().orElse("/");
        this.httpPort = Integer.parseInt(arguments.stream().filter(arg -> arg.startsWith("--http-port")).map(arg -> arg.substring(arg.indexOf('=') + 1)).findAny().orElse("8080"));

        commands.addAll(arguments);

        return commands.toArray(new String[0]);
    }

    private void waitForReadiness() throws MalformedURLException {
        URL contextRoot = new URL("http://localhost:" + httpPort + ("/" + relativePath + "/realms/master/").replace("//", "/"));
        HttpURLConnection connection = null;
        long startTime = System.currentTimeMillis();

        while (true) {
            if (System.currentTimeMillis() - startTime > getStartTimeout()) {
                throw new IllegalStateException(
                        "Timeout [" + getStartTimeout() + "] while waiting for Quarkus server");
            }

            try {
                // wait before checking for opening a new connection
                Thread.sleep(1000);
                if ("https".equals(contextRoot.getProtocol())) {
                    HttpsURLConnection httpsConnection = (HttpsURLConnection) (connection = (HttpURLConnection) contextRoot.openConnection());
                    httpsConnection.setSSLSocketFactory(createInsecureSslSocketFactory());
                    httpsConnection.setHostnameVerifier(createInsecureHostnameVerifier());
                } else {
                    connection = (HttpURLConnection) contextRoot.openConnection();
                }

                connection.setReadTimeout((int) getStartTimeout());
                connection.setConnectTimeout((int) getStartTimeout());
                connection.connect();

                if (connection.getResponseCode() == 200) {
                    LOGGER.infof("Keycloak is ready at %s", contextRoot);
                    break;
                }
            } catch (Exception ignore) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    private long getStartTimeout() {
        return TimeUnit.SECONDS.toMillis(120);
    }

    private HostnameVerifier createInsecureHostnameVerifier() {
        return new HostnameVerifier() {
            @Override
            public boolean verify(String s, SSLSession sslSession) {
                return true;
            }
        };
    }

    private SSLSocketFactory createInsecureSslSocketFactory() throws IOException {
        TrustManager[] trustAllCerts = new TrustManager[] {new X509TrustManager() {
            public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
            }

            public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }
        }};

        SSLContext sslContext;
        SSLSocketFactory socketFactory;

        try {
            sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            socketFactory = sslContext.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IOException("Can't create unsecure trust manager");
        }
        return socketFactory;
    }

    private boolean isRunning() {
        return keycloak != null && keycloak.isAlive();
    }

    private void asyncReadOutput() {
        shutdownOutputExecutor();
        outputExecutor = Executors.newSingleThreadExecutor();
        outputExecutor.execute(this::readOutput);
    }

    private void shutdownOutputExecutor() {
        if (outputExecutor != null) {
            outputExecutor.shutdown();
            try {
                outputExecutor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException cause) {
                throw new RuntimeException("Failed to terminate output executor", cause);
            } finally {
                outputExecutor = null;
            }
        }
    }

    private void reset() {
        outputStream.clear();
        errorStream.clear();
        exitCode = -1;
        keycloak = null;
        shutdownOutputExecutor();
    }

    private Path prepareDistribution() {
        try {
            Path distRootPath = Paths.get(System.getProperty("java.io.tmpdir")).resolve("kc-tests");
            distRootPath.toFile().mkdirs();
            File distFile = Maven.resolveArtifact("org.keycloak", "keycloak-server-x-dist", "zip")
                    .map(Artifact::getFile)
                    .orElseThrow(new Supplier<RuntimeException>() {
                        @Override
                        public RuntimeException get() {
                            return new RuntimeException("Could not obtain distribution artifact");
                        }
                    });
            String distDirName = distFile.getName().replace("keycloak-server-x-dist", "keycloak.x");
            Path distPath = distRootPath.resolve(distDirName.substring(0, distDirName.lastIndexOf('.')));

            if (reCreate || !distPath.toFile().exists()) {
                distPath.toFile().delete();
                ZipUtils.unzip(distFile.toPath(), distRootPath);
            }

            // make sure kc.sh is executable
            distPath.resolve("bin").resolve("kc.sh").toFile().setExecutable(true);

            return distPath;
        } catch (Exception cause) {
            throw new RuntimeException("Failed to prepare distribution", cause);
        }
    }

    private void readOutput() {
        try (
                BufferedReader outStream = new BufferedReader(new InputStreamReader(keycloak.getInputStream()));
                BufferedReader errStream = new BufferedReader(new InputStreamReader(keycloak.getErrorStream()));
        ) {
            while (keycloak.isAlive()) {
                readStream(outStream, outputStream);
                readStream(errStream, errorStream);
            }
        } catch (Throwable cause) {
            throw new RuntimeException("Failed to read server output", cause);
        }
    }

    private void readStream(BufferedReader reader, List<String> stream) throws IOException {
        String line;

        while (reader.ready() && (line = reader.readLine()) != null) {
            stream.add(line);
            System.out.println(line);
        }
    }

    /**
     * The server is configured to redirect errors to output stream. This adds a limitation when checking whether a
     * message arrived via error stream.
     *
     * @param arguments the list of arguments to run the server
     * @throws Exception if something bad happens
     */
    private void startServer(List<String> arguments) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(getCliArgs(arguments));
        ProcessBuilder builder = pb.directory(distPath.resolve("bin").toFile());

        builder.environment().put("KEYCLOAK_ADMIN", "admin");
        builder.environment().put("KEYCLOAK_ADMIN_PASSWORD", "admin");

        FileUtils.deleteDirectory(distPath.resolve("data").toFile());

        keycloak = builder.start();
    }
}
