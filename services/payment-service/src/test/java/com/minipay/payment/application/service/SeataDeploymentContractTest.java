package com.minipay.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SeataDeploymentContractTest {

    @Test
    void serverUsesTheDedicatedDatabaseAccountCreatedByMysqlBootstrap() throws IOException {
        Path repositoryRoot = findRepositoryRoot();
        String serverConfiguration = Files.readString(
                repositoryRoot.resolve("docker/seata/application.yml"));
        String mysqlBootstrap = Files.readString(
                repositoryRoot.resolve("docker/mysql/core/init/001-create-databases.sh"));
        String localMysqlBootstrap = Files.readString(
                repositoryRoot.resolve("docker/init-local-seata-user.sh"));
        String composeConfiguration = Files.readString(repositoryRoot.resolve("compose.yaml"));

        assertThat(mysqlBootstrap)
                .contains("CREATE USER IF NOT EXISTS 'seata'@'%'")
                .contains("GRANT ALL PRIVILEGES ON seata.* TO 'seata'@'%'");
        assertThat(serverConfiguration)
                .contains("user: seata")
                .contains("password: ${SEATA_DB_PASSWORD:123456}");
        assertThat(localMysqlBootstrap)
                .contains("CREATE USER IF NOT EXISTS 'seata'@'%'")
                .contains("GRANT ALL PRIVILEGES ON seata.* TO 'seata'@'%'")
                .contains("SEATA_DB_PASSWORD");
        assertThat(composeConfiguration)
                .contains("SEATA_DB_PASSWORD: ${SEATA_DB_PASSWORD:-seata}")
                .contains("init-local-seata-user.sh:/docker-entrypoint-initdb.d/02-init-seata-user.sh:ro");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("docker/seata/application.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("MiniPay repository root could not be located");
    }
}
