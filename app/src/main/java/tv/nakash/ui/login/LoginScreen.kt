package tv.nakash.ui.login

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tv.nakash.data.local.Account
import tv.nakash.data.local.AccountStore
import tv.nakash.data.remote.ApiProvider
import tv.nakash.data.repo.CatalogRepository
import tv.nakash.data.repo.EpgRepository
import tv.nakash.ui.theme.NakashColors
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val store: AccountStore, private val api: ApiProvider, private val catalog: CatalogRepository, private val epg: EpgRepository,
) : ViewModel() {
    val status = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    /** Authenticate at the explicit server URL. Catalog loads continue after sign-in. */
    fun signIn(server: String, user: String, pass: String) = viewModelScope.launch {
        if (busy.value) return@launch
        if(server.isBlank() || user.isBlank() || pass.isBlank()) {
            status.value="יש למלא שרת, שם משתמש וסיסמה"; return@launch
        }
        busy.value = true; status.value = "מתחברים…"
        val candidates = listOf(Account.normalizeBaseUrl(server))
        var ok = false
        for (base in candidates.distinct()) {
            val candidate = Account(base, user.trim(), pass)
            val auth = runCatching { api.authenticate(candidate) }.getOrNull()
            if (auth?.userInfo?.isActive == true) { store.save(candidate); ok = true; break }
        }
        if (!ok) { status.value = "הפרטים לא התקבלו — בדוק שרת, שם משתמש וסיסמה"; busy.value = false; return@launch }
        status.value = "טוענים ערוצים…"; runCatching { catalog.syncLive() }
        // Home appears now (account is set); the rest fills in behind it.
        launch { runCatching { catalog.syncVod() }; runCatching { catalog.syncSeries() }; runCatching { epg.syncXmltv() } }
        busy.value = false
    }
}

@Composable
fun LoginScreen(vm: LoginViewModel = hiltViewModel()) {
    var server by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var user by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()
    val initialFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { initialFocus.requestFocus() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (!busy) {
            keyboard?.hide()
            focusManager.clearFocus()
            vm.signIn(server, user, pass)
        }
        Unit
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(NakashColors.Bg,NakashColors.S1))).imePadding().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
        val wide = maxWidth > 700.dp
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        if(wide) Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("NakashTV",style=MaterialTheme.typography.displayLarge,color=NakashColors.Accent)
            Text("המסך הגדול.\nכל מה שאוהבים לראות.",style=MaterialTheme.typography.headlineMedium)
            Text("התחבר עם הפרטים שקיבלת מספק הטלוויזיה שלך.",color=NakashColors.Muted)
        }
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if(!wide) Text("NakashTV", style = MaterialTheme.typography.headlineMedium, color = NakashColors.Accent)
            Text("שלושה פרטים מהספק, פעם אחת.", style = MaterialTheme.typography.bodyLarge, color = NakashColors.Muted)
            val colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NakashColors.Accent, unfocusedBorderColor = NakashColors.Muted,
                focusedTextColor = NakashColors.Text, unfocusedTextColor = NakashColors.Text,
                focusedLabelColor = NakashColors.Accent, unfocusedLabelColor = NakashColors.Muted,
                cursorColor = NakashColors.Accent,
            )
            val fieldModifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if(event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) { keyboard?.show(); true } else false
            }
            val next = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) })
            OutlinedTextField(server, { server = it }, modifier = fieldModifier.focusRequester(initialFocus),
                label = { Text("שרת", color = NakashColors.Muted) }, singleLine = true, colors = colors,
                keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), keyboardActions = next)
            OutlinedTextField(user, { user = it }, modifier = fieldModifier,
                label = { Text("שם משתמש", color = NakashColors.Muted) }, singleLine = true, colors = colors,
                keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false, imeAction = ImeAction.Next), keyboardActions = next)
            OutlinedTextField(pass, { pass = it }, modifier = fieldModifier,
                label = { Text("סיסמה", color = NakashColors.Muted) }, singleLine = true, colors = colors,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }))
            Spacer(Modifier.height(4.dp))
            Button(onClick = submit, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = androidx.tv.material3.ButtonDefaults.colors(
                    containerColor = NakashColors.Accent, contentColor = androidx.compose.ui.graphics.Color.Black,
                    focusedContainerColor = androidx.compose.ui.graphics.Color.White,
                    focusedContentColor = androidx.compose.ui.graphics.Color.Black,
                )) { Text(if (busy) "מתחברים…" else "כניסה") }
            status?.let { Text(it, color = if (it.startsWith("הפרטים")) NakashColors.Live else NakashColors.Muted) }
        }
    }
    }
}
