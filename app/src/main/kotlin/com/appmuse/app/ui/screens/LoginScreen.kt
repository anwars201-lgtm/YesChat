package com.appmuse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appmuse.app.data.ApiError
import com.appmuse.app.ui.AuthState
import com.appmuse.app.ui.AuthViewModel
import com.appmuse.app.ui.theme.AppSpacing

// Phrase-book chrome. Single-language builds translate these in place;
// multi-locale builds rewrite them to per-locale resource reads (Android
// resolves the device locale natively via values-<qualifier>/). Keep new
// user-visible composable-scope copy in this form.
private const val CHROME_CREATE_ACCOUNT = "Create account"
private const val CHROME_WELCOME_BACK = "Welcome back"
private const val CHROME_SIGN_UP_TO_GET_STARTED = "Sign up to get started"
private const val CHROME_LOG_IN_TO_CONTINUE = "Log in to continue"
private const val CHROME_NAME = "Name"
private const val CHROME_EMAIL = "Email"
private const val CHROME_PASSWORD = "Password"
private const val CHROME_AT_LEAST_8_CHARACTERS = "At least 8 characters"
private const val CHROME_SIGN_UP = "Sign up"
private const val CHROME_LOG_IN = "Log in"
private const val CHROME_HAVE_AN_ACCOUNT = "Have an account? Log in"
private const val CHROME_NEW_HERE = "New here? Create an account"

/**
 * Auth screen — login + register against the shared backend (POST auth/login,
 * auth/register). A reference for the form pattern: hoisted state, the ViewModel
 * runs the call, errors render from AuthState.Unauthenticated.error. The agent
 * restyles this per the user's chosen design (swap the brand mark / copy).
 *
 * UX contract worth keeping when you restyle:
 *   - surface per-field errors (ApiError.Validation.fieldErrors) under the field,
 *   - guide a new user to "Create account" (there is no demo login — a fresh
 *     install has no account, so sign-up is the way in),
 *   - validate the 8-char password client-side so the user isn't bounced by the
 *     backend just to learn the rule.
 */
@Composable
fun LoginScreen(
    vm: AuthViewModel,
    state: AuthState,
    submitting: Boolean,
) {
    var registerMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val error = (state as? AuthState.Unauthenticated)?.error
    val fieldErrors = (error as? ApiError.Validation)?.fieldErrors ?: emptyMap()
    // Client-side mirror of the backend's registerSchema (password.min(8)) so the
    // user sees the rule before a round-trip, not after a vague rejection.
    val passwordTooShort = registerMode && password.isNotEmpty() && password.length < 8
    val canSubmit = !submitting &&
        email.isNotBlank() &&
        password.isNotBlank() &&
        (!registerMode || (name.isNotBlank() && password.length >= 8))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppSpacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Brand mark — a simple themed badge. The agent can drop a logo here.
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.height(AppSpacing.base))
        Text(
            if (registerMode) CHROME_CREATE_ACCOUNT else CHROME_WELCOME_BACK,
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(AppSpacing.xs))
        Text(
            if (registerMode) CHROME_SIGN_UP_TO_GET_STARTED else CHROME_LOG_IN_TO_CONTINUE,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(AppSpacing.lg))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(AppSpacing.lg)) {
                if (registerMode) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(CHROME_NAME) },
                        singleLine = true,
                        isError = fieldErrors.containsKey("name"),
                        supportingText = fieldErrors["name"]?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(CHROME_EMAIL) },
                    singleLine = true,
                    isError = fieldErrors.containsKey("email"),
                    supportingText = fieldErrors["email"]?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AppSpacing.sm))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(CHROME_PASSWORD) },
                    singleLine = true,
                    isError = fieldErrors.containsKey("password") || passwordTooShort,
                    supportingText = {
                        val msg = fieldErrors["password"]
                            ?: if (registerMode) CHROME_AT_LEAST_8_CHARACTERS else null
                        if (msg != null) Text(msg)
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Top-level error (e.g. "Invalid email or password") — only when it
                // isn't already shown inline against a specific field.
                if (error != null && fieldErrors.isEmpty()) {
                    Spacer(Modifier.height(AppSpacing.sm))
                    Text(
                        error.userMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Spacer(Modifier.height(AppSpacing.lg))
                Button(
                    onClick = {
                        if (registerMode) vm.register(email.trim(), password, name.trim().ifEmpty { null })
                        else vm.login(email.trim(), password)
                    },
                    enabled = canSubmit,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Text(if (registerMode) CHROME_SIGN_UP else CHROME_LOG_IN)
                    }
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.base))
        TextButton(onClick = { registerMode = !registerMode }, enabled = !submitting) {
            Text(
                if (registerMode) CHROME_HAVE_AN_ACCOUNT else CHROME_NEW_HERE,
                textAlign = TextAlign.Center,
            )
        }
    }
}
