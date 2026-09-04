package com.minipay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class MigrationVersionUniquenessTest {

    @Test
    void migrationVersionsAreUnique() throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql");

        Map<String, Long> versions = Arrays.stream(migrations)
                .map(Resource::getFilename)
                .filter(name -> name != null)
                .collect(Collectors.groupingBy(
                        name -> name.substring(1, name.indexOf("__")),
                        Collectors.counting()));

        assertThat(versions)
                .as("Flyway migration versions")
                .allSatisfy((version, count) -> assertThat(count)
                        .as("migration version %s", version)
                        .isEqualTo(1L));
    }
}
