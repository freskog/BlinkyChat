@file:OptIn(ExperimentalPermissionsApi::class, ExperimentalPermissionsApi::class,
    ExperimentalPermissionsApi::class, ExperimentalPermissionsApi::class
)

package org.freskog.blinkychat

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider

import org.freskog.blinkychat.ui.theme.BlinkyChatTheme

import ai.picovoice.porcupine.*
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainScreen"
    }

    var tts: TextToSpeech? = null

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    fun handleSpeechIntent(intent: Intent?) {
        val viewModel = ViewModelProvider(this).get(BinkyViewModel::class.java)
        val newStatus = intent?.getStringExtra("Status")

        when(newStatus) {
            "ERROR" -> {
                val error = intent.getStringExtra("Message").orEmpty()
                Log.e(TAG, "PicoWordService encountered an error: ${error}")
                viewModel.status.value = Status.ERROR
            }
            "WAKE_WORD_DETECTED" -> {
                Log.d(TAG, "Wake word detected")
                viewModel.status.value = Status.LISTENING
                viewModel.chatText.value = ""
            }
            "PARTIAL_SPEECH" -> {
                Log.i(TAG, "Speech detected")
                val detected = intent.getStringExtra("Speech")
                viewModel.chatText.value = viewModel.chatText.value + " " + detected
            }
            "FULL_SPEECH" -> {
                Log.i(TAG, "Speech detected")
                val detected = intent.getStringExtra("Speech")
                viewModel.chatText.value = detected.orEmpty()
                viewModel.sendText(detected.orEmpty())
                viewModel.status.value = Status.WAITING
            }
        }

    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleSpeechIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleSpeechIntent(intent = intent)

        val viewModel = ViewModelProvider(this).get(BinkyViewModel::class.java)

        tts?.let {
            it.shutdown()  // Shut down the previous instance if it exists
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                viewModel.setTTS(tts!!)
            } else if(status == TextToSpeech.ERROR) {
                Log.e(TAG, "Oh noes, can't initialized TTS!")
            }
        }

        setContent {
            BlinkyChatTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        PermissionScreen(viewModel, navController::navigate)
                    }
                    composable("settings") {
                        SettingsScreen(viewModel, navController::popBackStack)
                    }
                }

            }
        }
    }
}

@Composable
fun PermissionScreen(viewModel: BinkyViewModel, navigate: (String) -> Unit, modifier: Modifier = Modifier) {
    SideEffect {
        Log.d("Compose", "Permission Screen recomposed")
    }

    val context = LocalContext.current

    val systemAlertPermissionGranted = remember { mutableStateOf(Settings.canDrawOverlays(context) ) }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.FOREGROUND_SERVICE,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
    )

    val launcher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) { _ ->
            if (!systemAlertPermissionGranted.value) {
                systemAlertPermissionGranted.value = Settings.canDrawOverlays(context)
            }
        }

    when {
        // Check for SYSTEM_ALERT_WINDOW permission first
        !systemAlertPermissionGranted.value -> {
            Column {
                Text("SYSTEM_ALERT_WINDOW permission is required for this feature to be available. Please grant it.")
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    launcher.launch(intent)
                }) {
                    Text("Request SYSTEM_ALERT_WINDOW Permission")
                }
            }
        }
        // Once SYSTEM_ALERT_WINDOW is granted, check for other permissions
        !permissionsState.allPermissionsGranted -> {
            Column {
                Text("Permissions required for this feature to be available. Please grant them.")
                Button(onClick = {
                    permissionsState.launchMultiplePermissionRequest()
                }) {
                    Text("Request Permissions")
                }
            }
        }
        // All permissions granted
        else -> {
            MainScreen(viewModel = viewModel, navigate = navigate, modifier)
        }
    }
}



@Composable
fun MainScreen(viewModel: BinkyViewModel, navigate: (String) -> Unit, modifier:Modifier = Modifier) {

    SideEffect {
        Log.d("Compose", "MainScreen recomposed")
    }

    val context = LocalContext.current

    val isServiceStarted = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!isServiceStarted.value) {
            val serviceIntent = Intent(context, PicoWordService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
            isServiceStarted.value = true
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopBar(onNavigateToSettings = { navigate("settings") })
        StatusHeader(status = viewModel.status.value)
        ChatArea(text = viewModel.chatText.value, modifier.weight(1f))
        TextEntryBox(viewModel = viewModel)
    }
}

enum class Status {
    LISTENING,
    WAITING,
    RESPONDING,
    ERROR,
    IDLE
}

@Composable
fun TopBar(onNavigateToSettings: () -> Unit, modifier: Modifier = Modifier) {
    SideEffect {
        Log.d("Compose", "TopBar recomposed")
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Icon(Icons.Default.Settings, contentDescription = "Settings",
            modifier = modifier.clickable { onNavigateToSettings() })
    }
}

@Composable
fun StatusHeader(status:Status, modifier:Modifier = Modifier) {
    SideEffect {
        Log.d("Compose", "StatusHeader recomposed")
    }

    Row( modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (status) {
            Status.LISTENING -> Text(text = "Listening...")
            Status.WAITING -> Text(text = "Waiting for response...")
            Status.RESPONDING -> Text(text = "Responding...")
            Status.ERROR -> Text(text = "Oh no, an error!")
            Status.IDLE -> Text(text = "")
        }
    }
}

@Composable
fun ChatArea(text: String, modifier: Modifier = Modifier) {
    SideEffect {
        Log.d("Compose", "ChatArea recomposed")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(Color.LightGray)
            .border(1.dp, Color.Black)
    ) {
        Column(modifier = modifier.verticalScroll(rememberScrollState())) {
            Text(text = text)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEntryBox(viewModel: BinkyViewModel, modifier: Modifier = Modifier) {

    SideEffect {
        Log.d("Compose", "TextEntryBox recomposed")
    }

    var inputText by remember { mutableStateOf("") }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextField(
            value = inputText,
            onValueChange = { inputText = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type your message...") }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = {
            viewModel.sendText(inputText)
            inputText = ""
        }) {
            Text("Send")
        }
    }
}

class BinkyViewModel(application: Application) : AndroidViewModel(application) {
    val context = application.applicationContext
    val chatText = mutableStateOf("")
    val status = mutableStateOf(Status.IDLE)
    val openAIKeyValue = mutableStateOf(getDefaultOpenaiKey())
    val silenceTimeoutValue = mutableStateOf(getDefaultSilenceTimeout())
    val systemMessageValue = mutableStateOf(getDefaultSystemMessage())
    val picoVoiceAPIKey = mutableStateOf(getDefaultPicoVoiceAPIKey())

    val openAI = OpenAI(
        token = openAIKeyValue.value,
        timeout = Timeout(socket = 60.seconds)
    )

    var tts: TextToSpeech? = null

    fun setTTS(ttsInstance: TextToSpeech) {
        this.tts = ttsInstance
    }

    fun speakText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
    }
    fun sendText(text: String) {
        viewModelScope.launch {
            val chatCompletionRequest = ChatCompletionRequest(
                model = ModelId("gpt-3.5-turbo"),
                messages = listOf(
                    ChatMessage(
                        role = ChatRole.System,
                        content = systemMessageValue.value
                    ),
                    ChatMessage(
                        role = ChatRole.User,
                        content = text
                    )
                )
            )
            try {
                val response = openAI.chatCompletion(chatCompletionRequest)
                val bestResponse = response.choices.first().message.content.orEmpty()
                chatText.value = bestResponse
                speakText(bestResponse)
                status.value = Status.IDLE
            } catch (e: Exception) {
                chatText.value = e.toString()
                status.value = Status.ERROR
            }
        }
    }

    private fun getDefaultOpenaiKey(): String {
        return getDefaultAPIKey("openai_key")
    }

    private fun getDefaultSilenceTimeout(): Float {
        val sharedPreferences = getApplication<Application>().getSharedPreferences("settings", Context.MODE_PRIVATE)
        return sharedPreferences.getFloat("silence_timeout", 3f)
    }

    private fun getDefaultSystemMessage(): String {
        val sharedPreferences = getApplication<Application>().getSharedPreferences("settings", Context.MODE_PRIVATE)
        return sharedPreferences.getString("system_message", "You are a helpful AI assistant.") ?: "You are a helpful AI assistant."
    }

    private fun getDefaultPicoVoiceAPIKey(): String {
        return getDefaultAPIKey("picovoice_key")
    }


    private fun getDefaultAPIKey(name:String): String {
        val sharedPreferences = getApplication<Application>().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val defaultKey = ApiKeys.retrieveAPIKey(name, context)
        if(!sharedPreferences.contains(name)) {
            with(sharedPreferences.edit()) {
                putString(name, defaultKey)
                apply()
            }
        }
        return sharedPreferences.getString(name, defaultKey) ?: defaultKey
    }

    fun updateOpenAIKey(value: String) {
        openAIKeyValue.value = value
    }

    fun updateSilenceTimeout(value: Float) {
        silenceTimeoutValue.value = value
    }

    fun updateSystemMessage(value: String) {
        systemMessageValue.value = value
    }

    fun updatePicoVoiceAPIKey(value:String) {
        picoVoiceAPIKey.value = value
    }

    fun saveSettings() {
        val sharedPref = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("openai_key", openAIKeyValue.value)
            putFloat("silence_timeOut", silenceTimeoutValue.value)
            putString("system_message", systemMessageValue.value)
            putString("picovoice_key", picoVoiceAPIKey.value)
            apply()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: BinkyViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = viewModel.openAIKeyValue.value,
            onValueChange = viewModel::updateOpenAIKey,
            label = { Text("OpenAI Key") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = viewModel.picoVoiceAPIKey.value,
            onValueChange = viewModel::updatePicoVoiceAPIKey,
            label = { Text("Picovoice Key") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Silence Detection Timeout:")
            Slider(
                value = viewModel.silenceTimeoutValue.value,
                onValueChange = viewModel::updateSilenceTimeout,
                valueRange = 0f..10f,
                steps = 10,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            OutlinedTextField(
                value = viewModel.systemMessageValue.value,
                onValueChange = viewModel::updateSystemMessage,
                label = { Text("System Message") },
                modifier = Modifier
                    .fillMaxSize(),
                maxLines = Int.MAX_VALUE
            )
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {

            Button(onClick = { onNavigateBack() }) {
                Text("Back")
            }

            Button(onClick = {
                viewModel.saveSettings()
                onNavigateBack()
            }) {
                Text("Save")
            }
        }
    }
}


@Preview(showBackground = true)
@Composable
fun BlinkyChatPreview() {
    BlinkyChatTheme {
        MainScreen(BinkyViewModel(application = Application()), navigate = {})
    }
}