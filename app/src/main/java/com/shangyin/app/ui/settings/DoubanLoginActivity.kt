package com.shangyin.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.shangyin.app.data.douban.DoubanClient
import com.shangyin.app.ui.theme.ShangYinTheme
import kotlinx.coroutines.launch

/**
 * 豆瓣登录 Activity：原生 UI + OkHttp 直调豆瓣密码登录 API。
 * 放弃 WebView 方案（Cookie 抓取不可靠、微信 OAuth 在移动浏览器被拦截）。
 *
 * 登录方式：手机号/邮箱 + 密码（豆瓣原生支持）
 * API: POST https://accounts.douban.com/j/mobile/login/basic
 *
 * 微信/微博登录不可用（微信 OAuth 在移动 WebView 里被官方拦截，微博同理），
 * 只保留密码登录（可用手机号或邮箱作为账号）。
 */
class DoubanLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShangYinTheme {
                PasswordLoginScreen(
                    onBack = { finish() },
                    onLoginSuccess = {
                        setResult(Activity.RESULT_OK)
                        Toast.makeText(this, "豆瓣登录成功", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onError = { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordLoginScreen(
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("豆瓣登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "使用豆瓣账号密码登录",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    "支持手机号或邮箱作为账号\n登录后搜索结果更全",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )

                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text("手机号或邮箱") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { tryLogin(account, password, loading, { loading = it }, onLoginSuccess, onError, scope) }
                    ),
                    singleLine = true
                )

                Button(
                    onClick = {
                        tryLogin(account, password, loading, { loading = it }, onLoginSuccess, onError, scope)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && account.isNotBlank() && password.isNotBlank()
                ) {
                    if (loading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(0.dp), strokeWidth = 2.dp)
                            Text("登录中…")
                        }
                    } else {
                        Text("登录豆瓣")
                    }
                }

                Text(
                    "注：微信/微博登录在手机浏览器中被官方限制，暂不支持",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

private fun tryLogin(
    account: String,
    password: String,
    loading: Boolean,
    setLoading: (Boolean) -> Unit,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    if (loading) return
    if (account.isBlank() || password.isBlank()) {
        onError("请输入账号和密码")
        return
    }
    setLoading(true)
    scope.launch {
        try {
            val result = DoubanClient.loginByPassword(account.trim(), password)
            when (result) {
                is com.shangyin.app.data.douban.LoginResult.Success -> onSuccess()
                is com.shangyin.app.data.douban.LoginResult.Failure -> onError(result.message)
            }
        } catch (e: Exception) {
            onError("登录异常：${e.message}")
        } finally {
            setLoading(false)
        }
    }
}
