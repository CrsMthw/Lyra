package com.crsmthw.lyra.ui.screens.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.crsmthw.lyra.R
import com.crsmthw.lyra.data.auth.SpotifyAuthManager
import com.crsmthw.lyra.data.local.EncryptedPrefs
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    encryptedPrefs    : EncryptedPrefs,
    authManager       : SpotifyAuthManager,
    onAuthenticated   : () -> Unit,
    showExpiredNotice : Boolean = false,
) {
    val scope          = rememberCoroutineScope()
    val keyboard       = LocalSoftwareKeyboardController.current

    var clientId       by remember { mutableStateOf(encryptedPrefs.clientId) }
    var showClientId   by remember { mutableStateOf(false) }
    var isLoading      by remember { mutableStateOf(false) }
    var errorMessage   by remember { mutableStateOf<String?>(null) }

    // ActivityResultLauncher for the Spotify auth browser
    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val (response, exception) = authManager.parseAuthResponse(result.data!!)
            when {
                response  != null -> scope.launch {
                    isLoading = true
                    val res = authManager.exchangeCodeForTokens(response)
                    isLoading = false
                    res.fold(
                        onSuccess = { onAuthenticated() },
                        onFailure = { errorMessage = it.message },
                    )
                }
                exception != null -> errorMessage = exception.message
                else              -> errorMessage = "Authentication cancelled"
            }
        } else {
            isLoading = false
        }
    }

    Scaffold(
        // contentWindowInsets = WindowInsets(0) → Scaffold won't consume system bar insets;
        // we apply statusBarsPadding ourselves on the column.
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .statusBarsPadding()
                .navigationBarsPadding()
                // imePadding shifts content up when soft keyboard opens
                .imePadding()
                .padding(horizontal = 32.dp),
            verticalArrangement   = Arrangement.Center,
            horizontalAlignment   = Alignment.CenterHorizontally,
        ) {

            // ── App icon ─────────────────────────────────────────────────────
            // Same look as Settings → About: the foreground logo bursting past its
            // adaptive-icon safe-zone padding on a 100dp rounded-square tile. The
            // vector is rendered at its FINAL size (150dp) and clipped down, rather
            // than Modifier.scale()-ing a 100dp raster up — scaling magnifies the
            // small rasterised bitmap and blurs it.
            Box(
                modifier         = Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter            = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier           = Modifier.requiredSize(150.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── App name ─────────────────────────────────────────────────────
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Text(
                stringResource(R.string.auth_welcome_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Session-expired notice ───────────────────────────────────────
            // Shown when the refresh token died (6-month expiry / revoked) and the
            // user was routed back here. Client ID is preserved → a single Connect tap.
            if (showExpiredNotice && errorMessage == null) {
                Spacer(Modifier.height(24.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text     = stringResource(R.string.auth_session_expired_notice),
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            } else {
                Spacer(Modifier.height(48.dp))
            }

            // ── Client ID field ──────────────────────────────────────────────
            OutlinedTextField(
                value            = clientId,
                onValueChange    = { clientId = it; errorMessage = null },
                label            = { Text(stringResource(R.string.auth_client_id_label)) },
                placeholder      = { Text(stringResource(R.string.auth_client_id_placeholder)) },
                singleLine       = true,
                visualTransformation = if (showClientId) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                trailingIcon     = {
                    IconButton(onClick = { showClientId = !showClientId }) {
                        Icon(
                            if (showClientId) Icons.Default.VisibilityOff
                            else              Icons.Default.Visibility,
                            contentDescription = "Toggle visibility",
                        )
                    }
                },
                keyboardOptions  = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction    = ImeAction.Done,
                ),
                keyboardActions  = KeyboardActions(onDone = { keyboard?.hide() }),
                modifier         = Modifier.fillMaxWidth(),
                isError          = errorMessage != null,
            )

            if (errorMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Helper text
            Text(
                text  = stringResource(R.string.auth_help_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            // ── Connect button ───────────────────────────────────────────────
            Button(
                onClick  = {
                    if (clientId.isBlank()) {
                        errorMessage = "Client ID cannot be empty"
                        return@Button
                    }
                    // Save encrypted before launching the browser
                    encryptedPrefs.clientId = clientId.trim()
                    keyboard?.hide()
                    isLoading = true
                    try {
                        val intent = authManager.buildAuthIntent(clientId.trim())
                        authLauncher.launch(intent)
                    } catch (e: Exception) {
                        isLoading     = false
                        errorMessage  = e.message ?: "Failed to open auth browser"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled  = !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color    = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.auth_connect_button))
                }
            }
        }
    }
}
