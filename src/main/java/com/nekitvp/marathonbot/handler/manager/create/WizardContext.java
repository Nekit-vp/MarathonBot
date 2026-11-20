package com.nekitvp.marathonbot.handler.manager.create;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WizardContext {

    private String name;
    private String description;
    private Integer durationDays;
    private String dateStart;        // ISO format "yyyy-MM-dd"
    private Long selectedMarathonId; // used on manager detail page
    private Long selectedUserId;     // selected participant
    private Long selectedGoalId;     // selected goal
    private Long selectedHistoryId;  // selected history record
    private Long pendingDeleteId;       // id pending deletion confirmation
    private Long pendingJoinMarathonId; // marathon id pending join confirmation
    private Boolean pendingGamificationEnabled; // explicit manager confirmation intent
    private String selectedDate;        // "yyyy-MM-dd" for history date selection
}
