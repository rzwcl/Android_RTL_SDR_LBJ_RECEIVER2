package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.decoder.EtaInfo
import com.example.decoder.TrainTelemetry
import com.example.ui.components.LiveTelemetryCard

@Composable
fun TrainInfoScreen(
    telemetry: TrainTelemetry,
    etaInfo: EtaInfo,
    currentStationKmText: String,
    onOpenTrainTypeRuleDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        LiveTelemetryCard(
            telemetry = telemetry,
            etaInfo = etaInfo,
            currentStationKmText = currentStationKmText,
            onOpenTrainTypeRuleDialog = onOpenTrainTypeRuleDialog
        )
    }
}
