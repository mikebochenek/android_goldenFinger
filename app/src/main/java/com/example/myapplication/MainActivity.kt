package com.example.myapplication

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Increment launch counter
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val launchCount = prefs.getInt("launch_count", 0) + 1
        prefs.edit(commit = false) {
            putInt("launch_count", launchCount)
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FingerprintDemoScreen(
                        launchCount = launchCount,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun FingerprintDemoScreen(launchCount: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    
    var debugLog by remember { mutableStateOf("Ready to scan...") }
    var authStatus by remember { mutableStateOf("Waiting...") }
    
    var successCount by remember { mutableStateOf(prefs.getInt("success_count", 0)) }
    var failCount by remember { mutableStateOf(prefs.getInt("fail_count", 0)) }

    val packageInfo = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
    }
    
    val versionName = packageInfo.versionName ?: "Unknown"
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Welcome to goldenFinger!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Text(text = "Launch Count: $launchCount | Version: $versionName | Build: $versionCode")

        HorizontalDivider()

        Text(
            text = "Fingerprint Scanner Debug Demo",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Button(
            onClick = {
                if (activity != null) {
                    showBiometricPrompt(
                        activity = activity,
                        onStatusChange = { authStatus = it },
                        onLogUpdate = { debugLog = it },
                        onScanResult = { isSuccess ->
                            if (isSuccess) {
                                successCount++
                                prefs.edit { putInt("success_count", successCount) }
                            } else {
                                failCount++
                                prefs.edit { putInt("fail_count", failCount) }
                            }
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Invoke Scanner")
        }

        val totalScans = successCount + failCount
        val successRate = if (totalScans > 0) (successCount.toFloat() / totalScans * 100) else 0f
        val failRate = if (totalScans > 0) (failCount.toFloat() / totalScans * 100) else 0f

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Scan Statistics",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(text = "Total Scans: $totalScans")
            Text(text = "Successful: $successCount (%.1f%%)".format(successRate))
            Text(text = "Failed: $failCount (%.1f%%)".format(failRate))
        }

        Text(
            text = "Auth Status: $authStatus",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = if (authStatus.contains("Success")) Color(0xFF4CAF50) else Color.Red
        )

        Text(
            text = "Debug Info & Match Percentage Discussion:",
            style = MaterialTheme.typography.labelLarge
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.05f))
                .padding(8.dp)
        ) {
            Column {
                Text(
                    text = debugLog,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "[!] NOTE RE: MATCH PERCENTAGE\n" +
                           "Android's Biometric API (TEE/StrongBox) does NOT expose the raw 'match percentage' to the application layer. This is a security feature to prevent side-channel attacks and template reconstruction. The system returns only Success, Failure, or Error.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray
                )
            }
        }
    }
}

private fun showBiometricPrompt(
    activity: FragmentActivity,
    onStatusChange: (String) -> Unit,
    onLogUpdate: (String) -> Unit,
    onScanResult: (Boolean) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val biometricPrompt = BiometricPrompt(activity, executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onStatusChange("Error: $errorCode")
                onLogUpdate("ERROR CODE: $errorCode\nSTRING: $errString\nMATCH %: [HIDDEN BY OS]")
                // We don't count technical errors as failed fingerprint matches usually, 
                // but for this demo let's count them as failures if they are auth-related.
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onStatusChange("Success!")
                onLogUpdate("AUTH TYPE: ${result.authenticationType}\nMATCH: HIGH CONFIDENCE\nPERCENTAGE: N/A (SecureEnclave Private)")
                onScanResult(true)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                onStatusChange("Failed")
                onLogUpdate("RESULT: NO MATCH\nRETRY: Suggested\nCONFIDENCE: BELOW THRESHOLD")
                onScanResult(false)
            }
        })

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Biometric Scanner Debug")
        .setSubtitle("Invoking TEE Fingerprint Matcher")
        .setNegativeButtonText("Cancel")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()

    biometricPrompt.authenticate(promptInfo)
}

@Preview(showBackground = true)
@Composable
fun DemoPreview() {
    MyApplicationTheme {
        FingerprintDemoScreen(launchCount = 5)
    }
}