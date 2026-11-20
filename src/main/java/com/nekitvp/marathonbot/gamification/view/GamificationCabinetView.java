package com.nekitvp.marathonbot.gamification.view;

import com.nekitvp.marathonbot.util.MarkdownUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable participant-facing cabinet projection. Dynamic values are escaped only at this view boundary. */
public record GamificationCabinetView(
        String marathonName,
        boolean finished,
        List<RewardItem> newlyShown,
        List<MedalItem> medals,
        List<AchievementItem> achievements,
        List<ProgressItem> nearbyProgress,
        StreakView streaks,
        ExpeditionView expedition
) {

    public static final int TELEGRAM_SAFE_LIMIT = 3_800;
    public static final int PAGE_SIZE = 8;

    public GamificationCabinetView {
        marathonName = Objects.requireNonNullElse(marathonName, "Марафон");
        newlyShown = List.copyOf(Objects.requireNonNull(newlyShown, "newlyShown"));
        medals = List.copyOf(Objects.requireNonNull(medals, "medals"));
        achievements = List.copyOf(Objects.requireNonNull(achievements, "achievements"));
        nearbyProgress = List.copyOf(Objects.requireNonNull(nearbyProgress, "nearbyProgress"));
        Objects.requireNonNull(streaks, "streaks");
        Objects.requireNonNull(expedition, "expedition");
    }

    public RenderedPage renderSummary() {
        StringBuilder raw = new StringBuilder("🏅 Мои награды\n\n")
                .append("🎯 ").append(marathonName).append('\n');
        if (finished) {
            raw.append("🏁 Марафон завершён — коллекция остаётся с тобой.\n");
        }
        raw.append("\n🏅 Медалей: ").append(medals.stream().mapToLong(MedalItem::count).sum())
                .append("\n🏆 Достижений: ").append(achievements.size())
                .append("\n🗺️ Маршрут: ").append(percent(expedition.teamRoutePercent()));
        if (!newlyShown.isEmpty()) {
            raw.append("\n\n✨ Новые награды:\n");
            newlyShown.forEach(item -> raw.append(item.label()).append('\n'));
        }
        return page(raw.toString(), 1, 1);
    }

    public RenderedPage renderMedals(int requestedPage) {
        List<String> lines = medals.stream()
                .map(medal -> medal.emoji() + " " + medal.title() + " ×" + medal.count())
                .toList();
        return renderCollection("🏅 Медали", "Коллекция пока пуста — первая медаль уже ждёт твоего сильного дня! ✨",
                lines, requestedPage);
    }

    public RenderedPage renderAchievements(int requestedPage) {
        List<String> lines = new ArrayList<>();
        achievements.forEach(item -> lines.add(item.label()));
        if (!nearbyProgress.isEmpty()) {
            lines.add("\n🎯 Ближайшие цели:");
            nearbyProgress.forEach(item -> lines.add(item.title() + ": " + item.current() + "/" + item.target()
                    + " " + progressBar(item.current(), item.target())));
        }
        return renderCollection("🏆 Достижения", "Пока здесь тихо — продолжай путь, и первое достижение появится совсем скоро! 🌱",
                lines, requestedPage);
    }

    public RenderedPage renderStreaks(int requestedPage) {
        List<String> lines = new ArrayList<>();
        lines.add("🔥 Отчёты");
        lines.add("Текущая серия: " + streaks.currentReportStreak());
        lines.add("Лучшая серия: " + streaks.bestReportStreak());
        if (streaks.goals().isEmpty()) {
            lines.add("\n🎯 Серии целей появятся после первых выполнений.");
        } else {
            lines.add("\n🎯 Лучшие серии целей:");
            streaks.goals().forEach(goal -> lines.add(goal.goalName() + ": " + goal.current() +
                    " сейчас, " + goal.best() + " максимум"));
        }
        return renderCollection("🔥 Серии", "Серии начнут расти с первого своевременного отчёта.", lines,
                requestedPage);
    }

    public RenderedPage renderExpedition(int requestedPage) {
        List<String> lines = new ArrayList<>();
        lines.add((finished ? "🏁 Маршрут завершён" : "🥾 Команда в пути"));
        lines.add("Пройдено: " + percent(expedition.teamRoutePercent()));
        lines.add(progressBar(expedition.teamRoutePercent().intValue(), 100));
        lines.add("⚡ Личная энергия: " + expedition.personalEnergy());
        lines.add("Сегодня: " + expedition.todayEnergy());
        if (expedition.checkpoints().isEmpty()) {
            lines.add("\n🏕️ Первый лагерь откроется на отметке 25%.");
        } else {
            lines.add("\n🚩 Пройденные точки:");
            expedition.checkpoints().forEach(checkpoint -> lines.add(checkpoint.label()));
        }
        return renderCollection("🗺️ Командная экспедиция", "Командная экспедиция ещё не стартовала.", lines,
                requestedPage);
    }

    private static RenderedPage renderCollection(String heading, String empty, List<String> lines, int requestedPage) {
        if (lines.isEmpty()) {
            return page(heading + "\n\n" + empty, 1, 1);
        }
        int totalPages = Math.max(1, (lines.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int actualPage = Math.max(1, Math.min(requestedPage, totalPages));
        int start = (actualPage - 1) * PAGE_SIZE;
        int end = Math.min(lines.size(), start + PAGE_SIZE);
        String body = String.join("\n", lines.subList(start, end));
        return page(heading + "\n\n" + body + (totalPages > 1
                ? "\n\nСтраница " + actualPage + " из " + totalPages : ""), actualPage, totalPages);
    }

    private static RenderedPage page(String raw, int page, int totalPages) {
        String escaped = MarkdownUtil.escape(raw);
        if (escaped.length() > TELEGRAM_SAFE_LIMIT) {
            escaped = escaped.substring(0, TELEGRAM_SAFE_LIMIT);
            while (escaped.endsWith("\\")) {
                escaped = escaped.substring(0, escaped.length() - 1);
            }
        }
        return new RenderedPage(escaped, page, totalPages);
    }

    private static String progressBar(long current, long target) {
        if (target <= 0) {
            return "░░░░░░░░░░";
        }
        int filled = (int) Math.max(0, Math.min(10, current * 10 / target));
        return "▓".repeat(filled) + "░".repeat(10 - filled);
    }

    private static String percent(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + "%";
    }

    public record RenderedPage(String text, int page, int totalPages) {
        public RenderedPage {
            Objects.requireNonNull(text, "text");
        }
    }

    public record RewardItem(String emoji, String title, String goalName) {
        public RewardItem {
            Objects.requireNonNull(emoji, "emoji");
            Objects.requireNonNull(title, "title");
        }

        public String label() {
            return emoji + " " + title + (goalName == null || goalName.isBlank() ? "" : " — " + goalName);
        }
    }

    public record MedalItem(String emoji, String title, long count) {
        public MedalItem {
            Objects.requireNonNull(emoji, "emoji");
            Objects.requireNonNull(title, "title");
            if (count < 1) throw new IllegalArgumentException("count must be positive");
        }
    }

    public record AchievementItem(String emoji, String title, String goalName) {
        public AchievementItem {
            Objects.requireNonNull(emoji, "emoji");
            Objects.requireNonNull(title, "title");
        }

        public String label() {
            return emoji + " " + title + (goalName == null || goalName.isBlank() ? "" : " — " + goalName);
        }
    }

    public record ProgressItem(String title, long current, long target) {
        public ProgressItem {
            Objects.requireNonNull(title, "title");
            if (current < 0 || target <= 0) throw new IllegalArgumentException("progress values are invalid");
        }
    }

    public record StreakView(int currentReportStreak, int bestReportStreak, List<GoalStreak> goals) {
        public StreakView {
            if (currentReportStreak < 0 || bestReportStreak < 0) throw new IllegalArgumentException("streak is negative");
            goals = List.copyOf(Objects.requireNonNull(goals, "goals"));
        }
    }

    public record GoalStreak(String goalName, int current, int best) {
        public GoalStreak {
            Objects.requireNonNull(goalName, "goalName");
            if (current < 0 || best < 0) throw new IllegalArgumentException("streak is negative");
        }
    }

    public record ExpeditionView(
            BigDecimal teamRoutePercent,
            long personalEnergy,
            int todayEnergy,
            List<RewardItem> checkpoints
    ) {
        public ExpeditionView {
            teamRoutePercent = Objects.requireNonNull(teamRoutePercent, "teamRoutePercent");
            checkpoints = List.copyOf(Objects.requireNonNull(checkpoints, "checkpoints"));
        }
    }
}
