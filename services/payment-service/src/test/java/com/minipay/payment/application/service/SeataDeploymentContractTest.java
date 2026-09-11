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
                repositoryRoot.resolve("deploy/compose-infra/seata/application.yml"));
        String mysqlBootstrap = Files.readString(
                repositoryRoot.resolve("deploy/compose-infra/init/minipay-bootstrap.sh"));
        String composeConfiguration = Files.readString(
                repositoryRoot.resolve("deploy/compose-infra/compose.yaml"));

        assertThat(mysqlBootstrap)
                .contains("SEATA_DB_USERNAME")
                .contains("SEATA_DB_PASSWORD")
                .contains("create_database_and_user")
                .contains("\"seata\"")
                .contains("\"${SEATA_DB_USERNAME}\"")
                .contains("\"${SEATA_DB_PASSWORD}\"");
        assertThat(serverConfiguration)
                .contains("user: seata")
                .contains("password: ${SEATA_DB_PASSWORD}");
        assertThat(composeConfiguration)
                .contains("SEATA_DB_USERNAME: \"${SEATA_DB_USERNAME:?SEATA_DB_USERNAME is required}\"")
                .contains("SEATA_DB_PASSWORD: \"${SEATA_DB_PASSWORD:?SEATA_DB_PASSWORD is required}\"")
                .contains("./init/minipay-bootstrap.sh:/docker-entrypoint-initdb.d/01-minipay-bootstrap.sh:ro")
                .contains("./seata/application.yml:/seata-server/resources/application.yml:ro");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("deploy/compose-infra/seata/application.yml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("MiniPay repository root could not be located");
    }
}
