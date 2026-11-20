package com.nekitvp.marathonbot.service;

import com.nekitvp.marathonbot.enumBot.StateBot;
import com.nekitvp.marathonbot.gamification.service.MarathonParticipationService;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;


@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final MarathonRepository marathonRepository;
    private final MarathonParticipationService marathonParticipationService;

    @Lazy
    @Autowired
    private GoalService goalService;


    @Transactional
    public StateBot getUserState(Long telegramUserId, Message message) {
        UserEntity userEntity = userRepository.findById(telegramUserId)
                .orElseGet(() -> userRepository.save(createUserEntity(message)));
        // Обновляем username и имя при каждом взаимодействии — они могут измениться в Telegram
        String newUserName = message.getFrom().getUserName();
        String newFirstName = message.getFrom().getFirstName();
        boolean changed = !Objects.equals(userEntity.getTelegramUserName(), newUserName)
                || !Objects.equals(userEntity.getTelegramFirstName(), newFirstName);
        if (changed) {
            userEntity.setTelegramUserName(newUserName);
            userEntity.setTelegramFirstName(newFirstName);
            userRepository.save(userEntity);
        }
        return userEntity.getState();
    }

    @Transactional
    public StateBot updateUserState(Long telegramUserId, StateBot state) {
        UserEntity userEntity = userRepository.findById(telegramUserId)
                .orElseThrow(() -> new RuntimeException("Акканут не найден: " + telegramUserId));
        userEntity.setState(state);
        userEntity.setCountChangeState(userEntity.getCountChangeState() + 1);
        userEntity.setCountChangeStateAll(userEntity.getCountChangeStateAll() + 1);
        userEntity.setUpdatedAt(LocalDateTime.now());
        userRepository.save(userEntity);
        return userEntity.getState();
    }

    @Transactional
    public boolean checkIsAdmin(Long id) {
        return userRepository.existsByTelegramIdAndAdminIsTrue(id);
    }

    @Transactional
    public UserEntity findByUserName(String userName) {
        return userRepository.findByTelegramUserName(userName)
                .orElse(null);
    }

    @Transactional
    public UserEntity findByTelegramId(Long telegramId) {
        return userRepository.findByTelegramId(telegramId).orElse(null);
    }

    private UserEntity createUserEntity(Message message) {
        return UserEntity.builder()
                .telegramId(message.getFrom().getId())
                .telegramUserName(message.getFrom().getUserName())
                .telegramFirstName(message.getFrom().getFirstName())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .state(StateBot.START)
                .build();
    }

    @Transactional
    public void saveAdmin(UserEntity user) {
        user.setAdmin(true);
        userRepository.save(user);
    }

    @Transactional
    public List<UserEntity> getAllUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public void resetCount() {
        List<UserEntity> users = userRepository.findAll().stream()
                .peek(user -> user.setCountChangeState(0L)).toList();
        userRepository.saveAll(users);
    }

    @Transactional(readOnly = true)
    public List<UserEntity> getUsersWhoHasActiveMarathon() {
        var todayTime = LocalDateTime.now();
        return userRepository.findAll().stream()
                .filter(user -> Objects.nonNull(user.getMarathonId()))
                .filter(user -> todayTime.isAfter(user.getMarathon().getDateStart()))
                .filter(user -> todayTime.isBefore(user.getMarathon().getDateEnd()))
                .toList();
    }

    @Transactional(readOnly = true)
    public UserEntity getUser(Long telegramId) {
        return getUserEntity(telegramId);
    }

    private UserEntity getUserEntity(Long telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new RuntimeException("Акканут не найден: " + telegramId));
    }

    @Transactional
    public List<UserEntity> getUsersByMarathonId(Long marathonId) {
        return userRepository.findByMarathonId(marathonId);
    }

    @Transactional(readOnly = true)
    public boolean userHasAnyMarathon(Long telegramId) {
        var user = getUserEntity(telegramId);
        return user.getMarathon() != null;
    }

    @Transactional
    public void updateInputContext(Long telegramId, String inputContext) {
        UserEntity userEntity = userRepository.findById(telegramId)
                .orElseThrow(() -> new RuntimeException("Акканут не найден: " + telegramId));
        userEntity.setInputContext(inputContext);
        userRepository.save(userEntity);
    }

    @Transactional
    public void saveUser(UserEntity user) {
        userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public Set<MarathonEntity> getManagedMarathons(Long telegramId) {
        UserEntity user = getUserEntity(telegramId);
        return new HashSet<>(user.getManagedMarathons());
    }

    /**
     * Найти пользователя по @username или Telegram ID
     */
    @Transactional(readOnly = true)
    public UserEntity findByInput(String input) {
        String trimmed = input.trim();
        String username = trimmed.startsWith("@") ? trimmed.substring(1) : null;
        if (username != null) {
            return userRepository.findByTelegramUserNameIgnoreCase(username).orElse(null);
        }
        try {
            Long telegramId = Long.parseLong(trimmed);
            return userRepository.findByTelegramId(telegramId).orElse(null);
        } catch (NumberFormatException e) {
            // не число — попробовать как username без @
            return userRepository.findByTelegramUserNameIgnoreCase(trimmed).orElse(null);
        }
    }

    /**
     * Добавить пользователя в марафон и создать ему цель «Отчет»
     */
    @Transactional
    public void addUserToMarathon(Long userId, Long marathonId) {
        UserEntity user = getUserEntity(userId);
        Long previousMarathonId = user.getMarathon() != null
                ? user.getMarathon().getId() : user.getMarathonId();
        MarathonEntity marathon = marathonRepository.findById(marathonId)
                .orElseThrow(() -> new RuntimeException("Марафон не найден: " + marathonId));
        user.setMarathon(marathon);
        userRepository.save(user);
        goalService.createReportGoalIfNotExists(user, marathon);
        marathonParticipationService.switchMarathon(
                previousMarathonId, marathonId, userId, LocalDateTime.now(ZoneId.of("Europe/Moscow")));
    }

    /**
     * Удалить пользователя из марафона
     */
    @Transactional
    public void removeUserFromMarathon(Long userId) {
        UserEntity user = getUserEntity(userId);
        Long marathonId = user.getMarathon() != null
                ? user.getMarathon().getId() : user.getMarathonId();
        user.setMarathon(null);
        userRepository.save(user);
        marathonParticipationService.leave(
                marathonId, userId, LocalDateTime.now(ZoneId.of("Europe/Moscow")));
    }

    /**
     * Добавить пользователя как менеджера марафона
     */
    @Transactional
    public void addMarathonManager(Long userId, Long marathonId) {
        UserEntity user = getUserEntity(userId);
        MarathonEntity marathon = marathonRepository.findById(marathonId)
                .orElseThrow(() -> new RuntimeException("Марафон не найден: " + marathonId));
        user.getManagedMarathons().add(marathon);
        userRepository.save(user);
    }
}
