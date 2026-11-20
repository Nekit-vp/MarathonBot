package com.nekitvp.marathonbot.enumBot;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MarathonStatus {
    DRAFT("📝 Черновик"),
    WAITING_START("⏳ Ожидает старта"),
    ACTIVE("🏃 Идёт"),
    FINISHED("🏁 Завершён");

    private final String displayName;
}
