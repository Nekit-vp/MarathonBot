package com.nekitvp.marathonbot.gamification.service;

import com.nekitvp.marathonbot.service.TemplatePhraseService;
import com.nekitvp.marathonbot.util.MarkdownUtil;
import com.nekitvp.marathonbot.util.MessageTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Renders curated MarkdownV2 while escaping dynamic values at this single boundary. */
@Service
public class GamificationMessageComposer {

    public static final int TELEGRAM_SAFE_LIMIT = 3_800;
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    private final TemplatePhraseService phrases;

    public GamificationMessageComposer(TemplatePhraseService phrases) {
        this.phrases = Objects.requireNonNull(phrases, "phrases");
    }

    public String composeRewardBlock(List<RewardLine> rewards, int variantSeed) {
        Objects.requireNonNull(rewards, "rewards");
        if (rewards.isEmpty()) {
            return "";
        }
        Map<String, AggregatedReward> aggregated = new LinkedHashMap<>();
        for (RewardLine reward : rewards) {
            String key = reward.emoji() + '\u0000' + reward.title() + '\u0000' + Objects.toString(reward.goalName(), "");
            aggregated.compute(key, (ignored, current) -> current == null
                    ? new AggregatedReward(reward, 1)
                    : new AggregatedReward(current.reward(), current.count() + 1));
        }
        List<String> lines = new ArrayList<>();
        aggregated.values().forEach(item -> {
            RewardLine reward = item.reward();
            StringBuilder line = new StringBuilder(reward.emoji()).append(' ')
                    .append(MarkdownUtil.escape(reward.title()));
            if (reward.goalName() != null && !reward.goalName().isBlank()) {
                line.append(" — ").append(MarkdownUtil.escape(reward.goalName()));
            }
            if (item.count() > 1) {
                line.append(" ×").append(item.count());
            }
            lines.add(line.toString());
        });
        return MessageTemplate.GAMIFICATION_REWARD_BLOCK.format(
                String.join("\n", lines),
                MarkdownUtil.escape(phrases.getGamificationPhrase(
                        TemplatePhraseService.GAMIFICATION_REWARD, variantSeed))).strip();
    }

    public String composeRouteProgress(String marathonName, int percent, int todayEnergy, int variantSeed) {
        int boundedPercent = Math.max(0, Math.min(100, percent));
        int boundedEnergy = Math.max(0, Math.min(10, todayEnergy));
        return MessageTemplate.GAMIFICATION_ROUTE_PROGRESS.format(
                progressBar(boundedPercent), boundedPercent, boundedEnergy,
                MarkdownUtil.escape(phrases.getGamificationPhrase(
                        TemplatePhraseService.GAMIFICATION_EXPEDITION, variantSeed))).strip();
    }

    /** Tail appended to the group report so a green day and its run stay visible to everyone. */
    public String composeReportTail(boolean greenToday, int greenStreak, int reportNumber) {
        if (!greenToday || reportNumber <= 0) {
            return "";
        }
        String run = greenStreak > 1 ? "💚 Зелёная серия: " + greenStreak : "💚 Зелёный день";
        return run + " · Отчёт №" + reportNumber;
    }

    public String composeStatisticsProgress(int percent) {
        int boundedPercent = Math.max(0, Math.min(100, percent));
        return "🥾 *Экспедиция*: " + progressBar(boundedPercent) + " " + boundedPercent + "%";
    }

    public String composeCheckpointCelebration(
            String marathonName,
            int percent,
            String emoji,
            String checkpointTitle
    ) {
        int boundedPercent = Math.max(0, Math.min(100, percent));
        return MessageTemplate.GAMIFICATION_CHECKPOINT.format(
                Objects.requireNonNullElse(emoji, "🚩"),
                MarkdownUtil.escape(Objects.requireNonNull(checkpointTitle, "checkpointTitle")),
                boundedPercent,
                progressBar(boundedPercent),
                MarkdownUtil.escape(Objects.requireNonNullElse(marathonName, "Марафон"))).strip();
    }

    /** Compact weekly block merged into the daily digest, not a standalone message. */
    public String composeWeeklySummary(LocalDate weekStart, LocalDate weekEnd, List<Highlight> highlights) {
        Objects.requireNonNull(weekStart, "weekStart");
        Objects.requireNonNull(weekEnd, "weekEnd");
        List<String> lines = groupHighlightLines(
                Objects.requireNonNull(highlights, "highlights"),
                "Команда продолжает путь — каждый шаг считается 🥾");
        return MessageTemplate.GAMIFICATION_WEEKLY_SUMMARY.format(
                formatRange(weekStart, weekEnd), String.join("\n", lines)).strip();
    }

    public String composeGreenLeader(String participantName, int streak) {
        if (streak <= 0) {
            return "";
        }
        return "🏆 Самая длинная зелёная серия: "
                + MarkdownUtil.escape(firstNameOnly(participantName))
                + " — " + streak;
    }

    /** Groups awards by reward so a team-wide achievement reads as one line, not one line per person. */
    private static List<String> groupHighlightLines(
            List<Highlight> highlights,
            String emptyPlaceholder
    ) {
        Map<String, List<String>> namesByReward = new LinkedHashMap<>();
        for (Highlight highlight : List.copyOf(highlights)) {
            String label = highlight.emoji() + ' ' + MarkdownUtil.escape(highlight.rewardTitle());
            if (!highlight.hint().isBlank()) {
                label += " \\(" + MarkdownUtil.escape(highlight.hint()) + "\\)";
            }
            List<String> names = namesByReward.computeIfAbsent(label, ignored -> new ArrayList<>());
            String name = MarkdownUtil.escape(firstNameOnly(highlight.participantName()));
            if (!names.contains(name)) {
                names.add(name);
            }
        }

        List<String> lines = new ArrayList<>();
        namesByReward.forEach((label, names) -> lines.add("• " + label + " — " + joinNames(names)));
        if (lines.isEmpty()) {
            lines.add(emptyPlaceholder);
        }
        return List.copyOf(lines);
    }

    /** Renders the single daily group message: yesterday's rewards, route progress and an optional extra block. */
    public List<String> composeDailyDigest(
            LocalDate activityDate,
            List<Highlight> highlights,
            String progressBlock,
            String extraBlock
    ) {
        Objects.requireNonNull(activityDate, "activityDate");
        List<String> lines = groupHighlightLines(
                Objects.requireNonNull(highlights, "highlights"),
                "Тихий день на маршруте — идём дальше 🥾");

        String tail = Objects.requireNonNullElse(progressBlock, "").strip();
        if (extraBlock != null && !extraBlock.isBlank()) {
            tail = tail.isEmpty() ? extraBlock.strip() : tail + "\n\n" + extraBlock.strip();
        }
        String rendered = MessageTemplate.GAMIFICATION_DAILY_DIGEST.format(
                formatDay(activityDate), String.join("\n", lines), tail).strip();
        return paginate(rendered);
    }

    /**
     * В сводках печатается только имя: фамилия из displayName делает строку длинной, а имена в марафоне
     * не повторяются.
     */
    static String firstNameOnly(String participantName) {
        String name = Objects.requireNonNullElse(participantName, "").strip();
        if (name.isEmpty()) {
            return "Участник";
        }
        int separator = name.indexOf(' ');
        return separator < 0 ? name : name.substring(0, separator);
    }

    /** Перечисляются все получатели: попасть в сводку и остаться в «и ещё N» обиднее, чем длинная строка. */
    private static String joinNames(List<String> names) {
        return String.join(", ", names);
    }

    public List<String> composeLaunchPresentation(String marathonName, int activeDays) {
        String rendered = MessageTemplate.GAMIFICATION_LAUNCH.format(
                MarkdownUtil.escape(Objects.requireNonNullElse(marathonName, "Марафон")),
                Math.max(1, activeDays)).strip();
        return paginate(rendered);
    }

    public List<String> paginate(String markdown) {
        Objects.requireNonNull(markdown, "markdown");
        if (markdown.length() <= TELEGRAM_SAFE_LIMIT) {
            return List.of(markdown);
        }
        List<String> pages = new ArrayList<>();
        int start = 0;
        while (start < markdown.length()) {
            int end = safeBoundary(markdown, start, Math.min(markdown.length(), start + TELEGRAM_SAFE_LIMIT));
            int newline = markdown.lastIndexOf('\n', end - 1);
            if (newline > start + TELEGRAM_SAFE_LIMIT / 2) {
                end = safeBoundary(markdown, start, newline + 1);
            }
            if (end <= start) {
                end = Character.offsetByCodePoints(markdown, start, 1);
            }
            pages.add(markdown.substring(start, end));
            start = end;
        }
        return List.copyOf(pages);
    }

    private static int safeBoundary(String value, int start, int candidate) {
        int end = candidate;
        if (end < value.length() && end > start
                && Character.isLowSurrogate(value.charAt(end))
                && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        int precedingBackslashes = 0;
        for (int index = end - 1; index >= start && value.charAt(index) == '\\'; index--) {
            precedingBackslashes++;
        }
        if ((precedingBackslashes & 1) == 1) {
            end--;
        }
        return end;
    }

    private static String progressBar(int percent) {
        int filled = Math.max(0, Math.min(10, percent / 10));
        return "▰".repeat(filled) + "▱".repeat(10 - filled);
    }

    private static String formatDay(LocalDate date) {
        return date.getDayOfMonth() + " " + date.getMonth().getDisplayName(TextStyle.FULL, RUSSIAN);
    }

    private static String formatRange(LocalDate start, LocalDate end) {
        String month = end.getMonth().getDisplayName(TextStyle.FULL, RUSSIAN);
        if (start.getMonth() == end.getMonth()) {
            return start.getDayOfMonth() + "–" + end.getDayOfMonth() + " " + month;
        }
        return start.getDayOfMonth() + " "
                + start.getMonth().getDisplayName(TextStyle.FULL, RUSSIAN) + " – "
                + end.getDayOfMonth() + " " + month;
    }

    public record RewardLine(String emoji, String title, String goalName) {
        public RewardLine {
            emoji = Objects.requireNonNullElse(emoji, "🏅");
            title = Objects.requireNonNull(title, "title");
        }
    }

    public record Highlight(String participantName, String emoji, String rewardTitle, String hint) {
        public Highlight {
            participantName = Objects.requireNonNullElse(participantName, "Участник");
            emoji = Objects.requireNonNullElse(emoji, "🏅");
            rewardTitle = Objects.requireNonNull(rewardTitle, "rewardTitle");
            hint = Objects.requireNonNullElse(hint, "");
        }

        public Highlight(String participantName, String emoji, String rewardTitle) {
            this(participantName, emoji, rewardTitle, "");
        }
    }

    private record AggregatedReward(RewardLine reward, int count) {
    }
}
