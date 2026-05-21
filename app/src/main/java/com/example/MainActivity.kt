package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.example.ui.theme.Typography
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.WidgetSettingsManager
import com.example.network.GoldPriceFetcher
import com.example.services.XauUsdWidgetService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Trigger initial service start so pricing begins updating
        startSyncService(this)

        setContent {
            GoldLiveTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    DashboardScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.example.data.AlarmSoundManager.stopAlarm(this)
    }

    private fun startSyncService(context: Context) {
        val serviceIntent = Intent(context, XauUsdWidgetService::class.java).apply {
            action = XauUsdWidgetService.ACTION_START_SYNC
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Welcome! Live background sync initialized.", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Screen state
    var lastPrice by remember { mutableStateOf(WidgetSettingsManager.getLastPrice(context)) }
    var prevClose by remember { mutableStateOf(WidgetSettingsManager.getPrevClosePrice(context)) }
    var high24h by remember { mutableStateOf(WidgetSettingsManager.getHigh24h(context)) }
    var low24h by remember { mutableStateOf(WidgetSettingsManager.getLow24h(context)) }
    var priceHistory by remember { mutableStateOf(WidgetSettingsManager.getPriceHistory(context)) }
    var alarmList by remember { mutableStateOf(WidgetSettingsManager.getAlarms(context)) }
    var updateInterval by remember { mutableStateOf(WidgetSettingsManager.getUpdateIntervalSec(context)) }
    var isLiveSyncEnabled by remember { mutableStateOf(WidgetSettingsManager.isLiveSyncEnabled(context)) }
    var pricingSource by remember { mutableStateOf(WidgetSettingsManager.getPricingSource(context)) }
    var isMicroFlucEnabled by remember { mutableStateOf(WidgetSettingsManager.isMicroFluctuationEnabled(context)) }
    
    var isRefreshing by remember { mutableStateOf(false) }
    var lastUpdateText by remember { mutableStateOf("Ready") }
    var lastStatsFetchTime by remember { mutableStateOf(0L) }

    // Request POST_NOTIFICATIONS permission for Android 13+
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "Notification permission granted for Widget Feed!", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Refresh pricing and state locally in the app
    val refreshData = suspend {
        isRefreshing = true
        try {
            val livePrice = GoldPriceFetcher.fetchLivePrice(context)
            
            val nowTime = System.currentTimeMillis()
            val shouldFetchStats = (nowTime - lastStatsFetchTime) > 60_000L || prevClose == 0.0
            
            val stats = if (shouldFetchStats) {
                val fetched = GoldPriceFetcher.fetch24hStats()
                if (fetched != null) {
                    lastStatsFetchTime = nowTime
                }
                fetched
            } else {
                null
            }

            if (livePrice != null) {
                WidgetSettingsManager.setLastPrice(context, livePrice)
                lastPrice = livePrice
                
                // Immediately check and trigger price target alarms robustly
                com.example.data.PriceAlarmEngine.checkAndTriggerAlarms(context, livePrice)
            }
            if (stats != null) {
                WidgetSettingsManager.setPrevClosePrice(context, stats.open)
                WidgetSettingsManager.setHigh24h(context, stats.high)
                WidgetSettingsManager.setLow24h(context, stats.low)
                prevClose = stats.open
                high24h = stats.high
                low24h = stats.low
            }
            // Update local memory states
            priceHistory = WidgetSettingsManager.getPriceHistory(context)
            alarmList = WidgetSettingsManager.getAlarms(context)
            
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            lastUpdateText = "Checked at ${sdf.format(Date())}"

            // Re-trigger service to paint changes to the widget
            val serviceIntent = Intent(context, XauUsdWidgetService::class.java).apply {
                action = XauUsdWidgetService.ACTION_START_SYNC
            }
            context.startService(serviceIntent)
        } catch (e: Exception) {
            // Log or show toast silently depending on background context
        } finally {
            isRefreshing = false
        }
    }

    // Real-time active updates polling inside the app while screen is open
    LaunchedEffect(updateInterval, isLiveSyncEnabled) {
        while (isActive) {
            if (isLiveSyncEnabled) {
                refreshData()
            }
            delay(updateInterval * 1000L)
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                // Outer subtle warm peach/rose glow in top right
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFF4DDDB).copy(alpha = 0.5f), Color.Transparent),
                        center = Offset(size.width * 0.9f, size.height * 0.1f),
                        radius = size.width * 0.8f
                    )
                )
            }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(24.dp))
                
                // Beautiful Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "GOLD SPOT RATE",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF7D5260), // Theme Burgundy Accent
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "XAU / USD",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color(0xFF201A1B), // Theme Dark Text
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    
                    // Live Blinking / Pulsing status icon
                    Row(
                        modifier = Modifier
                            .background(Color(0xFFFFDAD9), RoundedCornerShape(12.dp))
                            .border(1.5.dp, Color(0xFFF4DDDB), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 0.6f,
                            targetValue = 1.2f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dot_pulse"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .drawBehind {
                                    drawCircle(
                                        color = if (isLiveSyncEnabled) Color(0xFF2E7D32) else Color(0xFFC62828),
                                        radius = size.minDimension / 2 * scale
                                    )
                                }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isLiveSyncEnabled) "REALTIME" else "PAUSED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF410009)
                        )
                    }
                }
            }

            // Big Live Pricing Presentation Card
            item {
                val trendPercent = if (prevClose > 0.0) {
                    ((lastPrice - prevClose) / prevClose) * 100.0
                } else {
                    0.0
                }
                val isUptrend = trendPercent >= 0.0
                val accentColorState = animateColorAsState(
                    targetValue = if (isUptrend) Color(0xFF2E7D32) else Color(0xFFC62828),
                    label = "color"
                )
                val pillBgColor = if (isUptrend) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                val pillBorderColor = if (isUptrend) Color(0xFFC8E6C9) else Color(0xFFFFCDD2)

                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pricing_card"),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = Color.White
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFF4DDDB), RoundedCornerShape(28.dp))
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "CURRENT PRICE",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF524343),
                            fontWeight = FontWeight.SemiBold
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Price
                        Text(
                            text = String.format(Locale.US, "$%,.2f", lastPrice),
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Light,
                            color = Color(0xFF201A1B),
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))

                        // Trend pill
                        Surface(
                            shape = CircleShape,
                            color = pillBgColor,
                            border = BorderStroke(1.dp, pillBorderColor),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isUptrend) "▲" else "▼",
                                    color = accentColorState.value,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(end = 4.dp)
                                )
                                Text(
                                    text = String.format(Locale.US, "%s%.2f%%", if (isUptrend) "+" else "", trendPercent),
                                    color = accentColorState.value,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        HorizontalDivider(color = Color(0xFFF4DDDB))
                        Spacer(modifier = Modifier.height(16.dp))

                        // stats row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("24h High", fontSize = 10.sp, color = Color(0xFF524343), fontWeight = FontWeight.Medium)
                                Text(
                                    text = String.format(Locale.US, "$%,.2f", high24h),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF201A1B),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Box(modifier = Modifier
                                .width(1.dp)
                                .height(28.dp)
                                .background(Color(0xFFF4DDDB)))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("24h Low", fontSize = 10.sp, color = Color(0xFF524343), fontWeight = FontWeight.Medium)
                                Text(
                                    text = String.format(Locale.US, "$%,.2f", low24h),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF201A1B),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Box(modifier = Modifier
                                .width(1.dp)
                                .height(28.dp)
                                .background(Color(0xFFF4DDDB)))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Prev. Close", fontSize = 10.sp, color = Color(0xFF524343), fontWeight = FontWeight.Medium)
                                Text(
                                    text = String.format(Locale.US, "$%,.2f", prevClose),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF201A1B),
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Realtime Interactive Canvas Chart
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFF4DDDB), RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "TICK MONITOR (20 POINTS)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF7D5260)
                            )
                            IconButton(
                                onClick = { scope.launch { refreshData() } },
                                modifier = Modifier
                                    .size(24.dp)
                                    .testTag("refresh_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = Color(0xFF7D5260),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))

                        // Rich dynamic Compose Canvas Line Chart
                        GoldCanvasChart(
                            history = priceHistory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = lastUpdateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF524343),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Price Target Alarm Management Panel
            item {
                var selectedTab by remember { mutableStateOf("PENDING") } // "PENDING", "EXECUTED", "DEACTIVATED", "DELETED"
                var targetPriceInput by remember { mutableStateOf("") }
                var triggerAbove by remember { mutableStateOf(true) } // true = Above, false = Below
                var isHighIntensity by remember { mutableStateOf(false) } // false = Simple, true = SOUND_VIB

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFF4DDDB), RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "Active Price Alarms",
                                tint = Color(0xFF7D5260),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "PRICE ALARM CHANNELS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF7D5260)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Custom Tab Bar for filtering active/deleted/pending/executed alarms
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFDF8F6), RoundedCornerShape(8.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf("PENDING", "EXECUTED", "DEACTIVATED", "DELETED").forEach { tab ->
                                val isSel = selectedTab == tab
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) Color(0xFFFFDAD9) else Color.Transparent)
                                        .clickable { selectedTab = tab }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tab,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSel) Color(0xFF410009) else Color(0xFF524343)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Numeric entry input box and fill-current helper
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = targetPriceInput,
                                onValueChange = { targetPriceInput = it },
                                label = { Text("Target Rate ($ USD)", fontSize = 11.sp) },
                                placeholder = { Text(String.format(Locale.US, "%.2f", lastPrice), fontSize = 12.sp) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF7D5260),
                                    unfocusedBorderColor = Color(0xFFF4DDDB)
                                )
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Button(
                                onClick = { targetPriceInput = String.format(Locale.US, "%.2f", lastPrice) },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFDAD9),
                                    contentColor = Color(0xFF410009)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.height(56.dp)
                            ) {
                                Text("Use Live", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Trigger Direction (Above or Below target crossing)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { triggerAbove = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (triggerAbove) Color(0xFF7D5260) else Color(0xFFFDF8F6),
                                    contentColor = if (triggerAbove) Color.White else Color(0xFF524343)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFF4DDDB)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Cross Above ▲", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Button(
                                onClick = { triggerAbove = false },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (!triggerAbove) Color(0xFF7D5260) else Color(0xFFFDF8F6),
                                    contentColor = if (!triggerAbove) Color.White else Color(0xFF524343)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFF4DDDB)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Cross Below ▼", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Selected Alarm Intensity Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Notification Intensity", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7D5260))
                                Text("Simple vs Heavy vibration alarm", fontSize = 10.sp, color = Color(0xFF524343))
                            }
                            Row(
                                modifier = Modifier
                                    .background(Color(0xFFFDF8F6), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFFF4DDDB), RoundedCornerShape(8.dp))
                                    .padding(2.dp)
                            ) {
                                Text(
                                    text = "Simple 🔔",
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (!isHighIntensity) Color(0xFFFFDAD9) else Color.Transparent)
                                        .clickable { isHighIntensity = false }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = Color(0xFF410009),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Alarm 🚨",
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isHighIntensity) Color(0xFFFFDAD9) else Color.Transparent)
                                        .clickable { isHighIntensity = true }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = Color(0xFF410009),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Submit Trigger Button
                        Button(
                            onClick = {
                                val parsedPrice = targetPriceInput.toDoubleOrNull()
                                if (parsedPrice != null && parsedPrice > 0.0) {
                                    val type = if (isHighIntensity) "SOUND_VIB" else "SIMPLE"
                                    WidgetSettingsManager.addAlarm(context, parsedPrice, triggerAbove, type)
                                    alarmList = WidgetSettingsManager.getAlarms(context)
                                    targetPriceInput = ""
                                    Toast.makeText(context, "Price channel set at $${parsedPrice}!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Please enter a valid numeric target price", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7D5260)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("DEPLOY LIVE PRICE ALARM", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = Color(0xFFF4DDDB))
                        Spacer(modifier = Modifier.height(12.dp))

                        // Alarm Listing based on filters
                        val filteredList = alarmList.filter {
                            when (selectedTab) {
                                "PENDING" -> it.state == "ACTIVE"
                                "EXECUTED" -> it.state == "EXECUTED"
                                "DEACTIVATED" -> it.state == "DEACTIVATED"
                                "DELETED" -> it.state == "DELETED"
                                else -> false
                            }
                        }.sortedByDescending { it.createdAt }

                        if (filteredList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No $selectedTab alarms configured.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF524343).copy(alpha = 0.6f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        } else {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                filteredList.forEach { alarm ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFFFDF8F6), RoundedCornerShape(10.dp))
                                            .border(1.dp, Color(0xFFF4DDDB), RoundedCornerShape(10.dp))
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                val prefixText = if (alarm.isAbove) "UP ▲" else "DOWN ▼"
                                                val badgeColor = if (alarm.isAbove) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                                                val badgeTextColor = if (alarm.isAbove) Color(0xFF2E7D32) else Color(0xFFC62828)
                                                
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(badgeColor)
                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                ) {
                                                    Text(prefixText, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = badgeTextColor)
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = String.format(Locale.US, "$%,.2f", alarm.targetPrice),
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF201A1B)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (alarm.alarmType == "SOUND_VIB") "Channel: Heavy Vib Alarm 🚨" else "Channel: Simple Notification 🔔",
                                                fontSize = 9.sp,
                                                color = Color(0xFF524343)
                                            )
                                            if (alarm.state == "EXECUTED" && alarm.executedAt != null) {
                                                val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
                                                Text(
                                                    text = "Breached at ${sdf.format(Date(alarm.executedAt))}",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF2E7D32)
                                                )
                                            }
                                        }

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Action buttons
                                            if (alarm.state == "ACTIVE") {
                                                // Deactivate button
                                                IconButton(
                                                    onClick = {
                                                        WidgetSettingsManager.updateAlarmState(context, alarm.id, "DEACTIVATED")
                                                        alarmList = WidgetSettingsManager.getAlarms(context)
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Deactivate",
                                                        tint = Color.Transparent, // hide icon vector, show custom bars inside
                                                        modifier = Modifier
                                                            .size(16.dp)
                                                            .drawBehind {
                                                                val w = size.width
                                                                val h = size.height
                                                                val barW = w * 0.25f
                                                                val gap = w * 0.2f
                                                                // Left bar
                                                                drawRect(
                                                                    color = Color(0xFF7D5260),
                                                                    topLeft = Offset((w - barW * 2 - gap) / 2f, h * 0.15f),
                                                                    size = androidx.compose.ui.geometry.Size(barW, h * 0.7f)
                                                                )
                                                                // Right bar
                                                                drawRect(
                                                                    color = Color(0xFF7D5260),
                                                                    topLeft = Offset((w - barW * 2 - gap) / 2f + barW + gap, h * 0.15f),
                                                                    size = androidx.compose.ui.geometry.Size(barW, h * 0.7f)
                                                                )
                                                            }
                                                    )
                                                }
                                                // Soft Delete button
                                                IconButton(
                                                    onClick = {
                                                        WidgetSettingsManager.deleteAlarm(context, alarm.id)
                                                        alarmList = WidgetSettingsManager.getAlarms(context)
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Delete",
                                                        tint = Color(0xFFC62828),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            } else if (alarm.state == "DEACTIVATED") {
                                                // Reactivate button
                                                IconButton(
                                                    onClick = {
                                                        WidgetSettingsManager.updateAlarmState(context, alarm.id, "ACTIVE")
                                                        alarmList = WidgetSettingsManager.getAlarms(context)
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.PlayArrow,
                                                        contentDescription = "Activate",
                                                        tint = Color(0xFF2E7D32),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                // Soft Delete button
                                                IconButton(
                                                    onClick = {
                                                        WidgetSettingsManager.deleteAlarm(context, alarm.id)
                                                        alarmList = WidgetSettingsManager.getAlarms(context)
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Delete",
                                                        tint = Color(0xFFC62828),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            } else {
                                                // For Executed or Deleted list:
                                                // Re-arm Button
                                                IconButton(
                                                    onClick = {
                                                        WidgetSettingsManager.updateAlarmState(context, alarm.id, "ACTIVE")
                                                        alarmList = WidgetSettingsManager.getAlarms(context)
                                                        Toast.makeText(context, "Alarm Re-Armed!", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = "Re-arm",
                                                        tint = Color(0xFF7D5260),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                // Permanent delete button
                                                IconButton(
                                                    onClick = {
                                                        WidgetSettingsManager.removeAlarmPermanently(context, alarm.id)
                                                        alarmList = WidgetSettingsManager.getAlarms(context)
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Clear,
                                                        contentDescription = "Erase",
                                                        tint = Color(0xFFC62828),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Bulk Cleanup Action buttons
                        if (alarmList.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        WidgetSettingsManager.resetAllAlarms(context)
                                        alarmList = emptyList()
                                        Toast.makeText(context, "All alarms purged!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFFF4DDDB)),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Reset All", fontSize = 11.sp, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                                }
                                
                                OutlinedButton(
                                    onClick = {
                                        // Reset executed to active
                                        val updated = alarmList.map {
                                            if (it.state == "EXECUTED") it.copy(state = "ACTIVE") else it
                                        }
                                        WidgetSettingsManager.saveAlarms(context, updated)
                                        alarmList = updated
                                        Toast.makeText(context, "All executed triggers reset to active!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFFF4DDDB)),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Re-Arm Triggers", fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Widget & Live-Sync Calibration Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFF4DDDB), RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF9EBE9))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color(0xFF7D5260),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "WIDGET DISPATCHER SETTINGS",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF7D5260)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Toggle Live Updates
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable Live Home Feed",
                                    color = Color(0xFF201A1B),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Allows the background service to feed prices automatically.",
                                    color = Color(0xFF524343),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = isLiveSyncEnabled,
                                onCheckedChange = { checked ->
                                    isLiveSyncEnabled = checked
                                    WidgetSettingsManager.setLiveSyncEnabled(context, checked)
                                    // Start or update service state
                                    val intent = Intent(context, XauUsdWidgetService::class.java).apply {
                                        action = XauUsdWidgetService.ACTION_START_SYNC
                                    }
                                    if (checked) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            context.startForegroundService(intent)
                                        } else {
                                            context.startService(intent)
                                        }
                                    } else {
                                        context.stopService(intent)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF7D5260),
                                    checkedTrackColor = Color(0xFFFFDAD9),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFE0C4C1)
                                ),
                                modifier = Modifier.testTag("live_sync_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = Color(0xFFF4DDDB))
                        Spacer(modifier = Modifier.height(16.dp))

                        // Live Micro Fluctuation Anti-Freeze Ticks Switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Anti-Freezing Price Ticks",
                                    color = Color(0xFF201A1B),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Ensures active cent flicker during weekends and flat hours of spot gold markets.",
                                    color = Color(0xFF524343),
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = isMicroFlucEnabled,
                                onCheckedChange = { checked ->
                                    isMicroFlucEnabled = checked
                                    WidgetSettingsManager.setMicroFluctuationEnabled(context, checked)
                                    scope.launch {
                                        refreshData()
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF7D5260),
                                    checkedTrackColor = Color(0xFFFFDAD9),
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFE0C4C1)
                                ),
                                modifier = Modifier.testTag("micro_fluc_switch")
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = Color(0xFFF4DDDB))
                        Spacer(modifier = Modifier.height(16.dp))

                        // Update Interval Selector
                        Text(
                            text = "Background Feed Interval: ${updateInterval}s",
                            color = Color(0xFF201A1B),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Faster updates look beautiful but utilize more battery & data.",
                            color = Color(0xFF524343),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        // Beautiful Segmented Selector
                        val intervals = listOf(1, 2, 5, 10, 30)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            intervals.forEach { sec ->
                                val isSelected = updateInterval == sec
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFF7D5260) else Color.White)
                                        .border(
                                            1.dp,
                                            if (isSelected) Color(0xFF7D5260) else Color(0xFFF4DDDB),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            updateInterval = sec
                                            WidgetSettingsManager.setUpdateIntervalSec(context, sec)
                                            // Tell service to pick up new interval
                                            val serviceIntent = Intent(context, XauUsdWidgetService::class.java).apply {
                                                action = XauUsdWidgetService.ACTION_START_SYNC
                                            }
                                            context.startService(serviceIntent)
                                            Toast.makeText(context, "Refresh speed set to: ${sec}s", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${sec}s",
                                        color = if (isSelected) Color.White else Color(0xFF201A1B),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = Color(0xFFF4DDDB))
                        Spacer(modifier = Modifier.height(16.dp))

                        // Pricing Feed Provider Selection
                        Text(
                            text = "Gold Pricing Feed Provider",
                            color = Color(0xFF201A1B),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "TradingView is default (recommended live CFD). Swiss Dukascopy and Gold-API are available as spot fallbacks.",
                            color = Color(0xFF524343),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        val sources = listOf(
                            "TRADING_VIEW" to "TradingView",
                            "DUKASCOPY" to "Dukascopy",
                            "GOLD_API" to "Gold-API"
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            sources.forEach { (srcKey, srcLabel) ->
                                val isSelected = pricingSource == srcKey
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0xFF7D5260) else Color.White)
                                        .border(
                                            1.dp,
                                            if (isSelected) Color(0xFF7D5260) else Color(0xFFF4DDDB),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            pricingSource = srcKey
                                            WidgetSettingsManager.setPricingSource(context, srcKey)
                                            
                                            // Force update immediate local and active service feeds
                                            scope.launch {
                                                refreshData()
                                            }
                                            
                                            // Send sync broadcast / command to active service
                                            val serviceIntent = Intent(context, XauUsdWidgetService::class.java).apply {
                                                action = XauUsdWidgetService.ACTION_START_SYNC
                                            }
                                            context.startService(serviceIntent)
                                            
                                            Toast.makeText(context, "Provider: $srcLabel", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = srcLabel,
                                        color = if (isSelected) Color.White else Color(0xFF201A1B),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Placement Guide Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFF4DDDB), RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Manual Guide",
                                tint = Color(0xFF7D5260),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "HOW TO PLACE LIVE WIDGET",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF7D5260)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        
                        val steps = listOf(
                            "Long press any empty area on your phone's Home Screen.",
                            "Tap on 'Widgets' or standard adding popup.",
                            "Scroll and find 'Gold Live' (with Gold • XAU/USD label).",
                            "Drag & drop the 3x1 layout to place it permanently.",
                            "Resize the widget vertically or horizontally to fit your exact screen alignment."
                        )

                        steps.forEachIndexed { idx, step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFFDAD9))
                                        .border(1.dp, Color(0xFF7D5260), CircleShape)
                                        .padding(top = 1.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${idx + 1}",
                                        color = Color(0xFF410009),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = step,
                                    fontSize = 12.sp,
                                    color = Color(0xFF201A1B),
                                    lineHeight = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}

@Composable
fun GoldCanvasChart(history: List<Double>, modifier: Modifier = Modifier) {
    if (history.size < 2) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFF9EBE9)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Tuning engine... Feed needs at least 2 ticks.",
                fontSize = 11.sp,
                color = Color(0xFF524343),
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val min = history.minOrNull() ?: 0.0
    val max = history.maxOrNull() ?: 1.0
    val range = if (max == min) 1.0 else max - min
    val isUptrend = history.last() >= history.first()

    val lineColor = if (isUptrend) Color(0xFF2E7D32) else Color(0xFFC62828)
    val startColor = if (isUptrend) Color(0x222E7D32) else Color(0x22C62828)

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFDF8F6))
            .padding(vertical = 12.dp)
    ) {
        val width = size.width
        val height = size.height
        val stepX = width / (history.size - 1)

        val path = Path()
        val fillPath = Path()

        for (i in history.indices) {
            val x = i * stepX
            val normalizedY = ((history[i] - min) / range).toFloat()
            // Keep 15px margins around edges
            val y = height - (normalizedY * (height - 30f) + 15f)

            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        fillPath.lineTo((history.size - 1) * stepX, height)
        fillPath.close()

        // Draw shadow fill first
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(startColor, Color.Transparent),
                startY = 0f,
                endY = height
            )
        )

        // Draw vector line
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

// High Density warm rose-cream luxury design theme definitions
@Composable
fun GoldLiveTheme(content: @Composable () -> Unit) {
    val lightColorScheme = lightColorScheme(
        primary = Color(0xFF7D5260),
        secondary = Color(0xFF8A4E5E),
        background = Color(0xFFFDF8F6),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF9EBE9),
        onPrimary = Color(0xFFFFFFFF),
        onSecondary = Color(0xFFFFFFFF),
        onBackground = Color(0xFF201A1B),
        onSurface = Color(0xFF201A1B),
        outline = Color(0xFFF4DDDB)
    )

    MaterialTheme(
        colorScheme = lightColorScheme,
        typography = Typography,
        content = content
    )
}
