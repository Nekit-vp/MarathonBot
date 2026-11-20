package com.nekitvp.marathonbot.service;

import com.nekitvp.marathonbot.enumBot.MarathonStatus;
import com.nekitvp.marathonbot.gamification.service.GamificationSchedulingService;
import com.nekitvp.marathonbot.integration.yandex.service.YandexGptService;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import jakarta.ws.rs.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.nekitvp.marathonbot.util.DateTimeUtil.isRestDay;
import static com.nekitvp.marathonbot.util.MessageTemplate.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarathonService {

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final MarathonRepository marathonRepository;
    private final HistoryService historyService;
    private final LetterSender letterSender;
    private final UserService userService;
    private final YandexGptService yandexGptService;
    private final GamificationSchedulingService gamificationSchedulingService;
    private final TransactionTemplate transactionTemplate;

    @Lazy
    @Autowired
    private GoalService goalService;

    @Value("${bot.name}")
    private String botName;

    @Value("${bot.username}")
    private String botUsername;

    @Transactional
    public void leftMarathon(Long groupId) {
        var entity = marathonRepository.findFirstByGroupIdOrderByIdDesc(groupId);
        if (entity.isPresent()) {
            var marathon = entity.get();
            marathon.setIsMember(false);
            marathonRepository.save(marathon);
        }
    }

    public Map<Long, String> getAllActiveMarathones() {
        return marathonRepository.findAll()
                .stream().filter(MarathonEntity::getIsMember)
                .collect(Collectors.toMap(MarathonEntity::getId, MarathonEntity::getName));
    }

    @Transactional(readOnly = true)
    public List<MarathonEntity> getAllMarathonsForMotivate() {
        return getPlayingMarathone();
    }

    @Transactional(readOnly = true)
    public List<MarathonEntity> getAllMarathonsForEveningQuestion() {
        return getPlayingMarathone().stream()
                .filter(MarathonEntity::getEveningQuestionsEnabled)
                .toList();
    }

    private List<MarathonEntity> getPlayingMarathone() {
        return marathonRepository.findAll().stream()
                .filter(marathon -> marathon.getDateStart() != null)
                .filter(marathon -> marathon.getDateEnd() != null)
                .filter(marathon -> LocalDateTime.now().isBefore(marathon.getDateEnd()))
                .filter(marathon -> LocalDateTime.now().isAfter(marathon.getDateStart()))
                .toList();
    }

    public void selectMarathon(Long id) {
        var entity = marathonRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Марафон не найден"));
        entity.setSelect(true);
        marathonRepository.save(entity);
    }

    public Long getIdSelectMarathon() {
        var entity = marathonRepository.findBySelectTrue()
                .orElseThrow(() -> new NotFoundException("Марафон не выбран"));
        entity.setSelect(false);
        marathonRepository.save(entity);
        return entity.getGroupId();
    }

    /**
     * Метод, для рассылки статистики активных марафонов
     */
    @Transactional
    public void sendStatistics() {
        getPlayingMarathone()
                .forEach(marathon -> {
                    try {
                        sendStatisticByMarathon(marathon);
                    } catch (RuntimeException exception) {
                        log.error("Statistics failed for marathon {}", marathon.getId(), exception);
                    }
                });
    }

    private void sendStatisticByMarathon(MarathonEntity marathon) {
        Map<String, Pair<Long, Long>> mapUsers = getResultPersonMap(marathon);
        String expeditionProgress = "";
        try {
            expeditionProgress = gamificationSchedulingService.statisticsProgress(marathon);
        } catch (RuntimeException exception) {
            log.error("Expedition progress unavailable for marathon {}; sending base statistics",
                    marathon.getId(), exception);
        }
        letterSender.sendStatistics(marathon, mapUsers, expeditionProgress);
    }

    private String sendFinalStatisticByMarathon(MarathonEntity marathon) {
        Map<String, Pair<Long, Long>> mapUsers = getResultPersonMap(marathon);
        return letterSender.sendFinalStatistics(marathon, mapUsers);
    }

    private Map<String, Pair<Long, Long>> getResultPersonMap(MarathonEntity marathon) {
        var users = userService.getUsersByMarathonId(marathon.getId());
        return users.stream()
                .collect(Collectors.toMap(
                        UserEntity::getTelegramFirstName,
                        historyService::getCountFailByUserInMarathon
                ));
    }

    /**
     * Метод, возвращающий булеан, а начался ли марафон
     */
    @Transactional
    public boolean marathonIsStarted(MarathonEntity marathon) {
        if (marathon.getDateStart() != null && marathon.getDateEnd() != null) {
            return LocalDateTime.now().isAfter(marathon.getDateStart()) &&
                    LocalDateTime.now().isBefore(marathon.getDateEnd());
        }
        return false;
    }

    @Transactional
    public void startMarathonsIfNeeded() {
        LocalDate today = LocalDate.now();
        var marathons = marathonRepository.findAll().stream()
                .filter(m -> m.getDateStart() != null)
                .filter(m -> m.getDateStart().toLocalDate().equals(today))
                .toList();

        if (marathons.isEmpty()) {
            log.info("No marathons starting today ({})", today);
            return;
        }

        marathons.forEach(m -> {
            // For new-style marathons waiting to start, check participant count
            if (m.getStatus() == MarathonStatus.WAITING_START) {
                List<UserEntity> participants = userService.getUsersByMarathonId(m.getId());
                if (participants.size() < 2) {
                    log.info("Postponing marathon {} — only {} participant(s)", m.getName(), participants.size());
                    m.setDateStart(m.getDateStart().plusDays(1));
                    m.setDateEnd(m.getDateEnd().plusDays(1));
                    marathonRepository.save(m);

                    if (m.getGroupId() != null) {
                        letterSender.publishEscape(m.getGroupId(), MARATHON_START_POSTPONED, m.getName());
                    }
                    if (m.getManagers() != null) {
                        m.getManagers().forEach(manager ->
                                letterSender.publishEscape(manager.getTelegramId(),
                                        MARATHON_START_POSTPONED_MANAGER, m.getName()));
                    }
                    return;
                }
                m.setStatus(MarathonStatus.ACTIVE);
                marathonRepository.save(m);
            }

            log.info("Starting marathon: {}", m.getName());
            if (m.getGroupId() != null) {
                letterSender.publishEscape(m.getGroupId(), MARATHON_START,
                        m.getName(),
                        m.getDateStart().format(DATE_TIME_FORMATTER),
                        m.getDateEnd().format(DATE_TIME_FORMATTER)
                );
            }
        });
    }

    public void finishMarathonsIfNeeded() {
        LocalDate today = LocalDate.now();
        var marathons = marathonRepository.findByStatusAndDateEndBefore(
                MarathonStatus.ACTIVE, today.atStartOfDay());

        if (marathons.isEmpty()) {
            log.info("No active marathons ended before {}", today);
            return;
        }

        marathons.forEach(candidate -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    finishMarathon(candidate);
                });
            } catch (RuntimeException exception) {
                log.error("Failed to finish marathon {}; other marathons will continue", candidate.getId(), exception);
            }
        });
    }

    private void finishMarathon(MarathonEntity m) {
        LocalDate finalDate = m.getDateEnd().toLocalDate();
        log.info("Finishing marathon: {} (final date: {})", m.getName(), finalDate);

        if (!isRestDay(m, finalDate)) {
            historyService.createFinalDayReports(m, finalDate);
        }

        gamificationSchedulingService.reconcileBeforeFinish(m, finalDate, LocalDateTime.now());

        m.setStatus(MarathonStatus.FINISHED);
        marathonRepository.save(m);
        log.info("Marathon {} marked as FINISHED", m.getId());

        try {
            if (m.getGroupId() != null) {
                letterSender.publishEscape(m.getGroupId(), MARATHON_FINISH, m.getName());
                sendFinalStatisticByMarathon(m);
            } else {
                log.warn("Marathon {} has no group chat; skipping final group messages", m.getId());
            }

            if (m.getManagers() != null) {
                m.getManagers().forEach(manager ->
                        letterSender.publishEscape(manager.getTelegramId(),
                                MARATHON_FINISH_MANAGER, m.getName())
                );
            }
        } catch (RuntimeException e) {
            log.error("Marathon {} was finished, but final notifications failed", m.getId(), e);
        }
    }

    @Transactional(readOnly = true)
    public void remindMarathonsStartingInHour() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        List<MarathonEntity> toRemind = marathonRepository.findAll().stream()
                .filter(m -> m.getDateStart() != null)
                .filter(m -> m.getGroupId() != null)
                .filter(m -> m.getDateStart().toLocalDate().equals(tomorrow))
                .toList();

        if (toRemind.isEmpty()) return;

        toRemind.forEach(m -> {
            String startStr = m.getDateStart().format(DATE_TIME_FORMATTER);

            letterSender.publishEscape(m.getGroupId(), MARATHON_START_REMINDER, m.getName(), startStr);

            if (m.getManagers() != null && !m.getManagers().isEmpty()) {
                m.getManagers().forEach(u ->
                        letterSender.publishEscape(u.getTelegramId(), MARATHON_START_REMINDER_MANAGER,
                                m.getName(), startStr));
            }
        });
    }

    @Transactional(readOnly = true)
    public void remindLastDay() {
        LocalDate today = LocalDate.now();

        List<MarathonEntity> lastDayMarathons = marathonRepository.findAll().stream()
                .filter(m -> m.getDateEnd() != null)
                .filter(m -> m.getDateEnd().toLocalDate().equals(today))
                .toList();

        if (lastDayMarathons.isEmpty()) {
            log.info("No marathons ending today ({})", today);
            return;
        }

        lastDayMarathons.forEach(m -> {
            log.info("Reminding last day for marathon: {}", m.getName());

            letterSender.publishEscape(m.getGroupId(), MARATHON_LAST_DAY, m.getName());

            if (m.getManagers() != null && !m.getManagers().isEmpty()) {
                m.getManagers().forEach(u ->
                        letterSender.publishEscape(u.getTelegramId(), MARATHON_LAST_DAY_MANAGER, m.getName())
                );
            }
        });
    }

    @Transactional
    public MarathonEntity createMarathon(String name, String description,
                                         LocalDate dateStart, int durationDays,
                                         Long creatorId) {
        String bindCode = generateBindCode();
        LocalDateTime start = dateStart.atStartOfDay();
        LocalDateTime end = dateStart.plusDays(durationDays).atTime(23, 59, 59);

        MarathonEntity marathon = MarathonEntity.builder()
                .name(name)
                .description(description)
                .dateStart(start)
                .dateEnd(end)
                .status(MarathonStatus.DRAFT)
                .creatorId(creatorId)
                .bindCode(bindCode)
                .bindCodeExpiry(LocalDateTime.now().plusDays(7))
                .freeFailCount(5)
                .penaltyAmount(100)
                .build();
        marathon = marathonRepository.save(marathon);

        UserEntity creator = userService.getUser(creatorId);
        creator.getManagedMarathons().add(marathon);
        userService.saveUser(creator);

        return marathon;
    }

    @Transactional
    public void handleBindCommand(Long chatId, Long userId, String text) {
        // Strip /bind prefix and optional @botname suffix
        String code = text.replaceFirst("(?i)/bind(@\\S+)?\\s*", "").strip();
        if (code.isEmpty()) {
            letterSender.publish(chatId, BIND_INVALID);
            return;
        }

        var opt = marathonRepository.findByBindCode(code);
        if (opt.isEmpty() || opt.get().getBindCodeExpiry() == null
                || opt.get().getBindCodeExpiry().isBefore(LocalDateTime.now())) {
            letterSender.publish(chatId, BIND_INVALID);
            return;
        }

        // Блокируем, только если в чате уже есть марафон в статусе WAITING_START или ACTIVE
        boolean chatBusy = marathonRepository.existsByGroupIdAndStatusIn(chatId,
                List.of(MarathonStatus.WAITING_START, MarathonStatus.ACTIVE));
        if (chatBusy) {
            letterSender.publish(chatId, BIND_CHAT_BUSY);
            return;
        }

        MarathonEntity marathon = opt.get();
        marathon.setGroupId(chatId);
        marathon.setIsMember(true);
        marathon.setBindCode(null);
        marathon.setBindCodeExpiry(null);
        if (marathon.getStatus() == MarathonStatus.DRAFT) {
            marathon.setStatus(MarathonStatus.WAITING_START);
        }
        marathonRepository.save(marathon);

        letterSender.publishEscape(chatId, BIND_SUCCESS, marathon.getName());

        String inviteLink = "https://t.me/" + botUsername + "?start=join_" + marathon.getId();
        String dateStart = marathon.getDateStart() != null ? marathon.getDateStart().format(DATE_FORMATTER) : "—";
        String dateEnd = marathon.getDateEnd() != null ? marathon.getDateEnd().format(DATE_FORMATTER) : "—";
        String description = marathon.getDescription() != null && !marathon.getDescription().isBlank()
                ? marathon.getDescription() : "Описание не указано";
        letterSender.publishWithUrlButtonEscape(chatId, BIND_MARATHON_INFO,
                "🔗 Вступить в марафон", inviteLink,
                marathon.getName(), description, dateStart, dateEnd,
                marathon.getFreeFailCount(), marathon.getPenaltyAmount());
    }

    @Transactional
    public String handleJoin(UserEntity user, Long marathonId) {
        var opt = marathonRepository.findById(marathonId);
        if (opt.isEmpty()) {
            return "Марафон не найден.";
        }
        MarathonEntity marathon = opt.get();
        if (marathon.getStatus() == MarathonStatus.FINISHED) {
            return "Этот марафон уже завершён.";
        }

        userService.addUserToMarathon(user.getTelegramId(), marathon.getId());

        if (marathon.getGroupId() != null) {
            letterSender.sendJoinMessage(marathon.getGroupId(), user.getEffectiveName());
        }

        return "Ты вступил в марафон «" + marathon.getName() + "»! Добро пожаловать! 🎉";
    }

    private String generateBindCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    @Transactional(readOnly = true)
    public void remindRestDay() {
        LocalDate today = LocalDate.now();

        List<MarathonEntity> marathons = getPlayingMarathone().stream()
                .filter(m -> isRestDay(m, today))
                .toList();

        if (marathons.isEmpty()) return;

        marathons.forEach(m ->
                letterSender.publish(m.getGroupId(), MARATHON_REST_DAY)
        );
    }
}
