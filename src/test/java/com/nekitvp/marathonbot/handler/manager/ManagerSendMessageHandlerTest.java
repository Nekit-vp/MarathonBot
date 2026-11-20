package com.nekitvp.marathonbot.handler.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nekitvp.marathonbot.bot.DeliveryWithFeedback;
import com.nekitvp.marathonbot.model.MarathonEntity;
import com.nekitvp.marathonbot.model.UserEntity;
import com.nekitvp.marathonbot.repository.MarathonRepository;
import com.nekitvp.marathonbot.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManagerSendMessageHandlerTest {

    private static final Long MANAGER_ID = 353779178L;
    private static final Long PRIVATE_CHAT_ID = 353779178L;
    private static final Long MARATHON_ID = 35L;
    private static final Long GROUP_ID = -5585882650L;

    @Mock
    private UserService userService;

    @Mock
    private MarathonRepository marathonRepository;

    private ManagerSendMessageHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ManagerSendMessageHandler(userService, marathonRepository, new ObjectMapper());
    }

    @Test
    void escapesManagerTextAndReturnsDeliveryWithRealResultFeedback() {
        UserEntity manager = managerWithContext("{\"selectedMarathonId\":35}");
        MarathonEntity marathon = MarathonEntity.builder()
                .id(MARATHON_ID)
                .name("Марафон v8")
                .groupId(GROUP_ID)
                .build();
        when(userService.findByTelegramId(MANAGER_ID)).thenReturn(manager);
        when(marathonRepository.findById(MARATHON_ID)).thenReturn(Optional.of(marathon));

        Object result = handler.handle(textUpdate("Лежебоки! Цели свои проверьте! И бегом выполнять 🏃"));

        DeliveryWithFeedback delivery = assertInstanceOf(DeliveryWithFeedback.class, result);
        assertEquals(GROUP_ID.toString(), delivery.delivery().getChatId());
        assertEquals("MarkdownV2", delivery.delivery().getParseMode());
        assertEquals("Лежебоки\\! Цели свои проверьте\\! И бегом выполнять 🏃",
                delivery.delivery().getText());
        assertEquals(PRIVATE_CHAT_ID.toString(), delivery.successFeedback().getChatId());
        assertEquals("✅ Сообщение отправлено в чат марафона «Марафон v8».",
                delivery.successFeedback().getText());
        assertEquals(PRIVATE_CHAT_ID.toString(), delivery.failureFeedback().getChatId());
        assertEquals("❌ Не удалось отправить сообщение в чат марафона «Марафон v8». Попробуй ещё раз.",
                delivery.failureFeedback().getText());
    }

    @Test
    void rejectsSendingWhenMarathonIsNotSelected() {
        when(userService.findByTelegramId(MANAGER_ID)).thenReturn(managerWithContext(null));

        Object result = handler.handle(textUpdate("Текст"));

        SendMessage response = assertInstanceOf(SendMessage.class, result);
        assertEquals("Марафон не выбран.", response.getText());
    }

    @Test
    void rejectsSendingWhenMarathonChatIsNotBound() {
        when(userService.findByTelegramId(MANAGER_ID))
                .thenReturn(managerWithContext("{\"selectedMarathonId\":35}"));
        when(marathonRepository.findById(MARATHON_ID)).thenReturn(Optional.of(
                MarathonEntity.builder().id(MARATHON_ID).name("Марафон v8").build()));

        Object result = handler.handle(textUpdate("Текст"));

        SendMessage response = assertInstanceOf(SendMessage.class, result);
        assertEquals("❌ Чат марафона не привязан. Сначала привяжи чат через /bind.", response.getText());
    }

    @Test
    void rejectsBlankMessage() {
        when(userService.findByTelegramId(MANAGER_ID))
                .thenReturn(managerWithContext("{\"selectedMarathonId\":35}"));
        when(marathonRepository.findById(MARATHON_ID)).thenReturn(Optional.of(
                MarathonEntity.builder().id(MARATHON_ID).name("Марафон v8").groupId(GROUP_ID).build()));

        Object result = handler.handle(textUpdate("   "));

        SendMessage response = assertInstanceOf(SendMessage.class, result);
        assertEquals("❌ Сообщение не может быть пустым.", response.getText());
    }

    private UserEntity managerWithContext(String inputContext) {
        return UserEntity.builder()
                .telegramId(MANAGER_ID)
                .inputContext(inputContext)
                .build();
    }

    private Update textUpdate(String text) {
        User from = new User();
        from.setId(MANAGER_ID);

        Chat chat = new Chat();
        chat.setId(PRIVATE_CHAT_ID);

        Message message = new Message();
        message.setFrom(from);
        message.setChat(chat);
        message.setText(text);

        Update update = new Update();
        update.setMessage(message);
        return update;
    }
}
