package com.momo.pink.test.etcd;

import com.opentable.etcd.EtcdConfiguration;
import com.opentable.etcd.EtcdInstance;
import mousio.etcd4j.EtcdClient;
import mousio.etcd4j.responses.EtcdException;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.etcd.config.EtcdConfigProperties;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

public class EtcdServer implements ApplicationContextInitializer, Ordered {
    private static final Logger logger = LoggerFactory.getLogger(EtcdServer.class);
    private EtcdInstance instance;

    @Autowired
    private EtcdClient client;

    @Autowired
    private EtcdConfigProperties properties;

    public void start() {
        EtcdConfiguration configuration = new EtcdConfiguration();
        //configuration.setDiscoveryUri("");
        Path dir;
        try {
            dir = Files.createTempDirectory("etcd");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        dir.toFile().deleteOnExit();
        configuration.setDataDirectory(dir);
        configuration.setDestroyNodeOnExit(true);
        configuration.setClientPort(4001);
        configuration.setHostname("127.0.0.1");
        String osName = System.getProperty("os.name");
        if (osName.toLowerCase().contains("windows")) {
            System.setProperty("os.name", "Windows");
        }
        instance = new EtcdInstance(configuration);
        System.setProperty("os.name", osName);
        try {
            instance.start();
            waitForServerInit();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try {
            migrate();
        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void migrate() throws URISyntaxException, IOException {

        ClassLoader classLoader = EtcdServer.class.getClassLoader();
        URL etcdURL = classLoader.getResource("etcd/config/");
        if (etcdURL != null) {
            Path rootDir = Paths.get(etcdURL.toURI());
            Files.walk(rootDir, FileVisitOption.FOLLOW_LINKS)
                .filter(p -> !Files.isDirectory(p))
                .forEach(p -> {
                    try {
                        doMigrate(rootDir, p);
                    } catch (IOException | EtcdException | TimeoutException e) {
                        throw new RuntimeException(e);
                    }
                });

        }
    }

    private void doMigrate(Path rootDir, Path file) throws IOException, TimeoutException, EtcdException {
        String etcdRootDir = "/" + properties.getPrefix();
        Path configDir = file.getParent();
        Path dir = configDir.subpath(rootDir.getNameCount(), configDir.getNameCount());
        String etcdConfigDir = String.join("/", etcdRootDir, dir.toString());

        try {
            client.getDir(etcdConfigDir)
                .send().get();
        } catch (EtcdException e) {
            client.putDir(etcdConfigDir)
                .send().get();
        }

        Properties properties = PropertiesLoaderUtils.loadProperties(new FileSystemResource(file.toFile()));
        Enumeration<Object> keys = properties.keys();
        while (keys.hasMoreElements()) {
            String key = (String) keys.nextElement();
            String value = properties.getProperty(key);
            client.put(String.join("/", etcdConfigDir, key), value)
                .send().get();
        }
    }


    public synchronized String getConnectString() {
        if (!instance.isRunning()) {
            throw new IllegalStateException("etcd server was not started");
        }
        return "http://127.0.0.1:" + instance.getClientPort();
    }

    private void waitForServerInit() throws IOException {
        final URL versionUrl = new URL(getConnectString() + "/version");
        IOException exc = null;
        for (int i = 0; i < 100; i++) {
            try {
                IOUtils.toString(versionUrl.openStream(), StandardCharsets.UTF_8);
                exc = null;
                break;
            } catch (IOException e) {
                exc = e;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        if (exc != null) {
            throw exc;
        }
    }

    public void stop() {
        logger.info("Start stop etcd server.");
        if (this.instance != null) {
            this.instance.stop();
        }
        logger.info("Stop etcd server successfully.");
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        this.start();
        applicationContext.addApplicationListener(e -> {
            if (e instanceof ContextClosedEvent) {
                this.stop();
            }
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 9;
    }
}
