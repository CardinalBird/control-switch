package com.controlswitch.domain.usecase

import com.controlswitch.domain.model.HomeButtonMode
import com.controlswitch.domain.repository.MirrorSettingsRepository

class SetHomeModeUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(mode: HomeButtonMode) = mirrorSettingsRepository.setHomeMode(mode)
}

class SetComboHoldKillAppEnabledUseCase(
    private val mirrorSettingsRepository: MirrorSettingsRepository,
) {
    operator fun invoke(enabled: Boolean) = mirrorSettingsRepository.setComboHoldKillAppEnabled(enabled)
}

