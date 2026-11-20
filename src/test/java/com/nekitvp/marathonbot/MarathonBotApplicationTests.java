package com.nekitvp.marathonbot;

import com.nekitvp.marathonbot.config.BotInitializer;
import com.nekitvp.marathonbot.gamification.support.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestContainerConfig.class)
@ActiveProfiles({"prod", "test"})
class MarathonBotApplicationTests {

    @MockBean
    private BotInitializer botInitializer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsWithPostgresAndLiquibaseSchema() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("marathonbot");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'users'",
                Integer.class))
                .isEqualTo(1);
    }

}
