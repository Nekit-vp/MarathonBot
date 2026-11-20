package com.nekitvp.marathonbot.gamification.support;

import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/** PostgreSQL-backed acceptance fixture with explicit marathon, roster, goal, and report facts. */
public final class GamificationTestFixture {

    private static final List<String> TABLES_IN_DELETE_ORDER = List.of(
            "gamification_notification",
            "marathon_participation",
            "expedition_state",
            "expedition_contribution",
            "reward_award",
            "history",
            "goal",
            "marathon_manager",
            "users",
            "motivation",
            "marathon"
    );

    private final JdbcTemplate jdbc;

    public GamificationTestFixture(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void clean() {
        TABLES_IN_DELETE_ORDER.forEach(table -> jdbc.update("DELETE FROM " + table));
    }

    public Long marathon(
            String name,
            LocalDate start,
            LocalDate end,
            boolean enabled,
            boolean restSaturday,
            boolean restSunday,
            MarathonStatus status
    ) {
        return jdbc.queryForObject("""
                INSERT INTO marathon(
                    name,group_id,date_start,date_end,gamification_enabled,status,
                    rest_on_saturday,rest_on_sunday,weekly_day_off_enabled
                ) VALUES (?,?,?,?,?,?,?,?,true) RETURNING id
                """, Long.class, name, -100_000L, start.atStartOfDay(), end.atTime(23, 59), enabled,
                status.name(), restSaturday, restSunday);
    }

    public void user(long userId, String name, Long marathonId, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO users(
                    telegram_id,telegram_first_name,display_name,marathon_id,created_at,updated_at,
                    state,admin,count_change_state,count_change_state_all
                ) VALUES (?,?,?,?,?,?,'START',false,0,0)
                """, userId, name, name, marathonId, createdAt, createdAt);
    }

    public void moveUser(long userId, Long marathonId, LocalDateTime changedAt) {
        jdbc.update("UPDATE users SET marathon_id=?, updated_at=? WHERE telegram_id=?", marathonId, changedAt, userId);
    }

    public void goal(long goalId, long userId, Long marathonId, String name, int position) {
        jdbc.update("""
                INSERT INTO goal(id,user_id,name,position,marathon_id,asked_count)
                VALUES (?,?,?,?,?,0)
                """, goalId, userId, name, position, marathonId);
    }

    public void renameGoal(long goalId, String name) {
        jdbc.update("UPDATE goal SET name=? WHERE id=?", name, goalId);
    }

    public void archiveGoal(long goalId, LocalDateTime archivedAt) {
        jdbc.update("UPDATE goal SET archived_at=? WHERE id=?", archivedAt, goalId);
    }

    public Long history(long goalId, LocalDate date, LocalTime time, Boolean done) {
        return jdbc.queryForObject(
                "INSERT INTO history(created_at,goal_id,done) VALUES (?,?,?) RETURNING id",
                Long.class, date.atTime(time), goalId, done);
    }

    public void correctHistory(long historyId, Boolean done) {
        jdbc.update("UPDATE history SET done=? WHERE id=?", done, historyId);
    }

    public void report(long reportGoalId, List<Long> personalGoalIds, LocalDate date, LocalTime time, boolean green) {
        Long reportHistoryId = history(reportGoalId, date, time, true);
        jdbc.update("UPDATE history SET report_submitted=true WHERE id=?", reportHistoryId);
        personalGoalIds.forEach(goalId -> history(goalId, date, time, green));
    }

    public void lateReport(long reportGoalId, List<Long> personalGoalIds, LocalDate date) {
        Long reportHistoryId = history(reportGoalId, date, LocalTime.NOON, false);
        jdbc.update("UPDATE history SET report_submitted=true WHERE id=?", reportHistoryId);
        personalGoalIds.forEach(goalId -> history(goalId, date, LocalTime.NOON, true));
    }

    public void personalDayOff(long reportGoalId, LocalDate date) {
        Long reportHistoryId = history(reportGoalId, date, LocalTime.of(9, 0), true);
        jdbc.update("UPDATE history SET day_off=true WHERE id=?", reportHistoryId);
    }

    public void participation(Long marathonId, long userId, LocalDateTime joinedAt) {
        jdbc.update(
                "INSERT INTO marathon_participation(marathon_id,user_id,joined_at) VALUES (?,?,?)",
                marathonId, userId, joinedAt);
    }

    public void closeParticipation(Long marathonId, long userId, LocalDateTime leftAt) {
        jdbc.update("""
                UPDATE marathon_participation
                SET left_at=?
                WHERE marathon_id=? AND user_id=? AND left_at IS NULL
                """, leftAt, marathonId, userId);
    }

    public void expedition(
            Long marathonId,
            LocalDate start,
            LocalDate end,
            int plannedActiveDays,
            boolean restSaturday,
            boolean restSunday
    ) {
        jdbc.update("""
                INSERT INTO expedition_state(
                    marathon_id,rule_version,activated_at,route_start_date,route_end_date,
                    rest_on_saturday,rest_on_sunday,planned_active_days,distance,
                    last_announced_checkpoint,version
                ) VALUES (?,1,?,?,?,?,?,?,0,0,0)
                """, marathonId, start.atStartOfDay(), start, end, restSaturday, restSunday, plannedActiveDays);
    }

    public long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }
}
