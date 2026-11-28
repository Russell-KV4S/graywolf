package com.nw5w.graywolf.wear

import android.app.RemoteInput
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import kotlinx.coroutines.launch

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bridge = PhoneBridgeClient(applicationContext)
        setContent {
            MaterialTheme {
                GraywolfWearApp(bridge)
            }
        }
    }
}

@Composable
private fun GraywolfWearApp(bridge: PhoneBridgeClient) {
    val navController = rememberSwipeDismissableNavController()
    val scope = rememberCoroutineScope()

    var phoneConnected by remember { mutableStateOf(false) }
    var stations by remember { mutableStateOf<List<WearStation>>(emptyList()) }
    var conversations by remember { mutableStateOf<List<WearConversation>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<String>>(emptyList()) }
    var recentCalls by remember { mutableStateOf<List<String>>(emptyList()) }
    var refreshNonce by remember { mutableStateOf(0) }

    suspend fun reloadSummary() {
        phoneConnected = bridge.hasConnectedPhone()
        if (!phoneConnected) return
        stations = bridge.fetchStations()
        bridge.fetchConversations()?.let { payload ->
            conversations = payload.conversations
            favorites = payload.favorites
            recentCalls = payload.recentCalls
        }
    }

    LaunchedEffect(refreshNonce) {
        reloadSummary()
    }

    DisposableEffect(Unit) {
        val listener = bridge.registerInboundListener {
            refreshNonce += 1
        }
        onDispose {
            bridge.unregisterInboundListener(listener)
        }
    }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = "home",
    ) {
        composable("home") {
            val listState = rememberScalingLazyListState()
            val totalUnread = conversations.sumOf { it.unreadCount }
            Scaffold(
                timeText = { TimeText() },
                positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
            ) {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Text(
                            text = "Graywolf APRS",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color(0xFF38BDF8),
                        )
                    }
                    item {
                        Text(
                            text = if (phoneConnected) "Phone RF Link Ready" else "Phone Disconnected",
                            fontSize = 11.sp,
                            color = if (phoneConnected) Color(0xFF22C55E) else Color(0xFFF59E0B),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Station History (${stations.size})", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = { Text("Newest first", maxLines = 1) },
                            colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF1E293B)),
                            onClick = { navController.navigate("stations") },
                        )
                    }
                    item {
                        val badge = if (totalUnread > 0) " ($totalUnread new)" else ""
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Messages$badge", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = { Text("${conversations.size} threads", maxLines = 1) },
                            colors = ChipDefaults.primaryChipColors(
                                backgroundColor = if (totalUnread > 0) Color(0xFF0369A1) else Color(0xFF1E293B),
                            ),
                            onClick = { navController.navigate("messages") },
                        )
                    }
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("New Message", maxLines = 1) },
                            colors = ChipDefaults.secondaryChipColors(),
                            onClick = { navController.navigate("new_message") },
                        )
                    }
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Refresh", maxLines = 1) },
                            colors = ChipDefaults.secondaryChipColors(),
                            onClick = { scope.launch { reloadSummary() } },
                        )
                    }
                }
            }
        }

        composable("stations") {
            val listState = rememberScalingLazyListState()
            Scaffold(
                timeText = { TimeText() },
                positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
            ) {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Text(
                            text = "Stations (${stations.size})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                    }
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Refresh List", maxLines = 1) },
                            colors = ChipDefaults.secondaryChipColors(),
                            onClick = { scope.launch { reloadSummary() } },
                        )
                    }
                    if (stations.isEmpty()) {
                        item {
                            Text(
                                text = "No stations heard yet",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    } else {
                        items(stations, key = { it.callsign }) { st ->
                            val distText = WearModels.formatDistanceAndBearing(st.distanceKm, st.bearingDeg)
                            val titleLine = buildString {
                                append(st.callsign)
                                if (st.isEmergency) append(" [EMG]")
                                append(" • ").append(st.ageLabel)
                            }
                            val subLine = listOf(distText, st.comment.ifBlank { st.statusText })
                                .filter { it.isNotBlank() }
                                .joinToString(" • ")
                                .ifBlank { if (st.gated) "APRS-IS" else "Direct RF" }
                            Chip(
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(titleLine, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                secondaryLabel = { Text(subLine, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                colors = ChipDefaults.primaryChipColors(
                                    backgroundColor = if (st.isEmergency) Color(0xFF7F1D1D) else Color(0xFF1E293B),
                                ),
                                onClick = {
                                    navController.navigate("station_detail/${Uri.encode(st.callsign)}")
                                },
                            )
                        }
                    }
                }
            }
        }

        composable(
            route = "station_detail/{callsign}",
            arguments = listOf(navArgument("callsign") { type = NavType.StringType }),
        ) { backStack ->
            val call = Uri.decode(backStack.arguments?.getString("callsign").orEmpty())
            val st = stations.firstOrNull { it.callsign.equals(call, ignoreCase = true) }
            val listState = rememberScalingLazyListState()
            Scaffold(
                timeText = { TimeText() },
                positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
            ) {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Text(
                            text = call,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (st?.isEmergency == true) Color(0xFFFCA5A5) else Color(0xFF38BDF8),
                        )
                    }
                    if (st != null) {
                        val dist = WearModels.formatDistanceAndBearing(st.distanceKm, st.bearingDeg)
                        item {
                            Text(
                                text = listOf("Heard ${st.ageLabel} ago", dist).filter { it.isNotBlank() }.joinToString(" • "),
                                fontSize = 12.sp,
                            )
                        }
                        item {
                            val pathLabel = if (st.gated) "APRS-IS gated" else if (st.via.isBlank()) "Direct RF" else "Via ${st.via}"
                            Text(text = pathLabel, fontSize = 11.sp, color = Color(0xFFCBD5E1))
                        }
                        if (st.comment.isNotBlank() || st.statusText.isNotBlank()) {
                            item {
                                Text(
                                    text = st.statusText.ifBlank { st.comment },
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8),
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            }
                        }
                    }
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Message $call", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF0284C7)),
                            onClick = {
                                navController.navigate("thread/dm/${Uri.encode(call)}")
                            },
                        )
                    }
                }
            }
        }

        composable("messages") {
            val listState = rememberScalingLazyListState()
            Scaffold(
                timeText = { TimeText() },
                positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
            ) {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Text("Messages", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("New Message", maxLines = 1) },
                            colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF0284C7)),
                            onClick = { navController.navigate("new_message") },
                        )
                    }
                    items(conversations, key = { "${it.threadKind}:${it.threadKey}" }) { conv ->
                        val title = buildString {
                            append(conv.alias.ifBlank { conv.threadKey })
                            if (conv.unreadCount > 0) append(" (${conv.unreadCount})")
                        }
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = {
                                Text(
                                    conv.lastSnippet.ifBlank { conv.threadKind.uppercase() },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = ChipDefaults.primaryChipColors(
                                backgroundColor = if (conv.unreadCount > 0) Color(0xFF0369A1) else Color(0xFF1E293B),
                            ),
                            onClick = {
                                navController.navigate("thread/${Uri.encode(conv.threadKind)}/${Uri.encode(conv.threadKey)}")
                            },
                        )
                    }
                }
            }
        }

        composable("new_message") {
            val listState = rememberScalingLazyListState()
            val callInputLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val raw = RemoteInput.getResultsFromIntent(result.data)
                    ?.getCharSequence("callsign_input")
                    ?.toString()
                    .orEmpty()
                val normalized = WearModels.normalizeCallsign(raw)
                if (normalized.isNotEmpty()) {
                    navController.navigate("thread/dm/${Uri.encode(normalized)}")
                }
            }

            Scaffold(
                timeText = { TimeText() },
                positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
            ) {
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Text("Start Message", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Voice / Type Callsign", maxLines = 1) },
                            colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF0284C7)),
                            onClick = {
                                val remoteInputs = listOf(
                                    RemoteInput.Builder("callsign_input")
                                        .setLabel("Callsign or tactical")
                                        .build(),
                                )
                                val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                                RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
                                callInputLauncher.launch(intent)
                            },
                        )
                    }
                    if (favorites.isNotEmpty()) {
                        item {
                            Text("Favorites", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                        items(favorites) { fav ->
                            Chip(
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(fav, maxLines = 1) },
                                colors = ChipDefaults.secondaryChipColors(),
                                onClick = { navController.navigate("thread/dm/${Uri.encode(fav)}") },
                            )
                        }
                    }
                    if (recentCalls.isNotEmpty()) {
                        item {
                            Text("Recent Stations", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        }
                        items(recentCalls.take(10)) { call ->
                            Chip(
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(call, maxLines = 1) },
                                colors = ChipDefaults.secondaryChipColors(),
                                onClick = { navController.navigate("thread/dm/${Uri.encode(call)}") },
                            )
                        }
                    }
                }
            }
        }

        composable(
            route = "thread/{kind}/{key}",
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("key") { type = NavType.StringType },
            ),
        ) { backStack ->
            val kind = Uri.decode(backStack.arguments?.getString("kind") ?: "dm")
            val key = Uri.decode(backStack.arguments?.getString("key").orEmpty())
            ThreadScreen(
                bridge = bridge,
                threadKind = kind,
                threadKey = key,
                refreshNonce = refreshNonce,
                onSent = { refreshNonce += 1 },
            )
        }
    }
}

@Composable
private fun ThreadScreen(
    bridge: PhoneBridgeClient,
    threadKind: String,
    threadKey: String,
    refreshNonce: Int,
    onSent: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    var messages by remember { mutableStateOf<List<WearMessage>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }

    fun sendReply(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || sending) return
        sending = true
        scope.launch {
            val sent = bridge.sendMessage(threadKey, clean)
            sending = false
            if (sent != null) {
                messages = messages + sent
                onSent()
            }
        }
    }

    LaunchedEffect(threadKind, threadKey, refreshNonce) {
        val loaded = bridge.fetchThreadMessages(threadKind, threadKey)
        messages = loaded
        for (m in loaded) {
            if (m.direction == "in" && m.unread && m.id > 0L) {
                bridge.markMessageRead(m.id)
            }
        }
    }

    val replyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val txt = RemoteInput.getResultsFromIntent(result.data)
            ?.getCharSequence("reply_input")
            ?.toString()
            .orEmpty()
        sendReply(txt)
    }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = if (threadKind == "tactical") "#$threadKey" else threadKey,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color(0xFF38BDF8),
                )
            }
            items(messages, key = { it.id }) { m ->
                val isOut = m.direction == "out"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isOut) Color(0xFF0369A1) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(10.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Column {
                            if (!isOut && threadKind == "tactical") {
                                Text(m.fromCall, fontSize = 10.sp, color = Color(0xFF7DD3FC), fontWeight = FontWeight.Bold)
                            }
                            Text(m.text, fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !sending,
                    label = { Text(if (sending) "Sending..." else "Voice / Type Reply", maxLines = 1) },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFF0284C7)),
                    onClick = {
                        val remoteInputs = listOf(
                            RemoteInput.Builder("reply_input")
                                .setLabel("Message $threadKey")
                                .setChoices(QuickRepliesStore.PRESETS.toTypedArray())
                                .build(),
                        )
                        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                        RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
                        replyLauncher.launch(intent)
                    },
                )
            }
            item {
                Text("Quick Replies", fontSize = 11.sp, color = Color(0xFF94A3B8))
            }
            items(QuickRepliesStore.PRESETS) { preset ->
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !sending,
                    label = { Text(preset, maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    onClick = { sendReply(preset) },
                )
            }
        }
    }
}
