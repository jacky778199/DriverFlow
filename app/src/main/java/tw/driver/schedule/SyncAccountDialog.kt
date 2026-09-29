package tw.driver.schedule

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
internal fun SyncAccountDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val currentUser by FirebaseSyncManager.currentUser.collectAsState()

    var accountInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (currentUser != null) Icons.Default.CloudDone else Icons.Default.Cloud,
                    contentDescription = null,
                    tint = if (currentUser != null) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (currentUser != null) "雲端同步（已連線）" else "雲端多裝置同步",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (currentUser != null) {
                    // 已登入狀態
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2E7D32).copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(8.dp).background(Color(0xFF2E7D32), CircleShape))
                                Text(
                                    "即時雙向同步中",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                            Text(
                                "目前帳號：${currentUser?.email}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "在另一台裝置（手機或平板）登入此帳號，排程訂單、花費及上下班工時就會自動即時同步。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    successMessage?.let {
                        Text(it, color = Color(0xFF2E7D32), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    if (isLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else {
                        // 立即備份上傳按鈕
                        FilledTonalButton(
                            onClick = {
                                isLoading = true
                                errorMessage = null
                                successMessage = null
                                val orderStore = OrderStore(context)
                                val expenseStore = ExpenseStore(context)
                                val appearancePrefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

                                val localRides = orderStore.load()
                                val localExpenses = expenseStore.load()
                                val localRental = expenseStore.loadRentalPlan()
                                val localWh = collectAllWorkHours(appearancePrefs)

                                FirebaseSyncManager.syncAllLocalToCloud(
                                    rides = localRides,
                                    expenses = localExpenses,
                                    rentalPlan = localRental,
                                    workHours = localWh
                                ) { result ->
                                    isLoading = false
                                    successMessage = result
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("立即備份所有本機資料至雲端")
                        }

                        // 登出按鈕
                        OutlinedButton(
                            onClick = {
                                FirebaseSyncManager.signOut()
                                successMessage = null
                                errorMessage = null
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("登出此裝置")
                        }
                    }
                } else {
                    // 未登入狀態：提供登入與註冊表單
                    Text(
                        "在手機與平板登入相同的司機帳號，兩台裝置將自動秒級即時同步！斷網時亦可照常離線使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = accountInput,
                        onValueChange = { accountInput = it; errorMessage = null },
                        label = { Text("電子郵件或司機代碼") },
                        placeholder = { Text("例：driver@gmail.com 或 ting8") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it; errorMessage = null },
                        label = { Text("密碼（至少 6 位數）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        modifier = Modifier.fillMaxWidth()
                    )

                    errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    if (isLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (accountInput.isBlank() || passwordInput.isBlank()) {
                                        errorMessage = "請輸入帳號與密碼"
                                        return@OutlinedButton
                                    }
                                    focusManager.clearFocus()
                                    isLoading = true
                                    errorMessage = null
                                    FirebaseSyncManager.register(
                                        accountInput = accountInput,
                                        passwordInput = passwordInput,
                                        onSuccess = {
                                            isLoading = false
                                            // 首次註冊成功後自動同步一次本地現有紀錄
                                            val orderStore = OrderStore(context)
                                            val expenseStore = ExpenseStore(context)
                                            val appearancePrefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                                            FirebaseSyncManager.syncAllLocalToCloud(
                                                orderStore.load(),
                                                expenseStore.load(),
                                                expenseStore.loadRentalPlan(),
                                                collectAllWorkHours(appearancePrefs)
                                            ) {}
                                        },
                                        onError = {
                                            isLoading = false
                                            errorMessage = it
                                        }
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("註冊帳號")
                            }

                            Button(
                                onClick = {
                                    if (accountInput.isBlank() || passwordInput.isBlank()) {
                                        errorMessage = "請輸入帳號與密碼"
                                        return@Button
                                    }
                                    focusManager.clearFocus()
                                    isLoading = true
                                    errorMessage = null
                                    FirebaseSyncManager.signIn(
                                        accountInput = accountInput,
                                        passwordInput = passwordInput,
                                        onSuccess = {
                                            isLoading = false
                                        },
                                        onError = {
                                            isLoading = false
                                            errorMessage = it
                                        }
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("登入同步")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("關閉")
            }
        }
    )
}
