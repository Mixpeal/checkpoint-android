package ng.checkpoint.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ng.checkpoint.engine.decide.Outcome
import kotlin.math.roundToInt

@Composable
fun CheckpointScreen(vm: CheckpointViewModel, modifier: Modifier = Modifier) {
    val boot by vm.boot.collectAsState()
    val online by vm.online.collectAsState()
    val q by vm.query.collectAsState()
    val lang by vm.lang.collectAsState()
    val copy = Copy.of(lang)

    Column(modifier.padding(horizontal = 20.dp)) {
        Header(
            packVersion = vm.packVersion,
            online = online,
            lang = lang,
            copy = copy,
            // One control cycles the three. With this few, a menu is more taps than taps.
            onLang = { vm.setLang(Lang.entries[(lang.ordinal + 1) % Lang.entries.size]) },
            // Nothing to clear on a blank page, and a button that does nothing is noise.
            onClear = if (q.text.isNotBlank() || q.outcome != null) vm::reset else null,
        )
        when (val state = boot) {
            is Boot.Ready -> Ready(vm, lang, copy)
            is Boot.Checking -> Notice(copy.checkingTitle, "${state.file}\n\n${copy.checkingBody}")
            is Boot.Fetching -> Notice(copy.fetchingTitle, "${state.file}  ${state.percent}%\n\n${copy.fetchingBody}")
            is Boot.Broken -> Notice(copy.brokenTitle, "${state.reason}\n\n${copy.brokenBody}")
            is Boot.Starting -> Notice(copy.startingTitle, copy.startingBody)
        }
    }
}

@Composable
private fun Header(
    packVersion: String,
    online: Boolean,
    lang: Lang,
    copy: Copy,
    onLang: () -> Unit,
    onClear: (() -> Unit)?,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Checkpoint", fontSize = 16.sp, color = Ink.fg, fontWeight = FontWeight.Medium)
            // The pack version takes the slack and gives it up first, so the two controls
            // to its right keep their room on a narrow phone.
            Text(
                if (packVersion.isEmpty()) "" else "  pack $packVersion",
                fontSize = 11.sp, color = Ink.t4,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                lang.label,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = Ink.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Ink.line, RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button) { onLang() }
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (onClear != null) {
                Text(
                    copy.clear,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = Ink.t2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, Ink.line, RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button) { onClear() }
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            // Offline is the normal, correct state here, so it is the one that gets colour.
            Text(
                if (online) copy.online else copy.offline,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = if (online) Ink.t3 else Ink.ok,
                modifier = Modifier
                    .border(1.dp, if (online) Ink.line else Ink.okDim, RoundedCornerShape(6.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
    }
}

@Composable
private fun Notice(title: String, body: String) {
    Column(Modifier.padding(top = 28.dp)) {
        Text(title, fontSize = 14.sp, color = Ink.fg, fontWeight = FontWeight.Medium)
        Text(body, fontSize = 13.sp, lineHeight = 20.sp, color = Ink.t2, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Ready(vm: CheckpointViewModel, lang: Lang, copy: Copy) {
    val q by vm.query.collectAsState()
    val speaking by vm.speaking.collectAsState()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberScrollState()

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.listen() }

    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Spacer(Modifier.height(18.dp))

        BasicTextField(
            value = q.text,
            onValueChange = vm::onText,
            textStyle = TextStyle(
                color = Ink.fg, fontSize = 14.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(Ink.accent),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { field ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Ink.line, RoundedCornerShape(12.dp))
                        .background(Ink.card).padding(14.dp)
                ) {
                    if (q.text.isEmpty()) {
                        Text(
                            copy.placeholder,
                            fontSize = 14.sp, lineHeight = 21.sp, color = Ink.t5,
                        )
                    }
                    field()
                }
            },
        )

        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Action(
                label = if (q.listening) copy.listening else copy.speak,
                filled = false,
                enabled = !q.thinking,
                modifier = Modifier.width(120.dp),
            ) {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) vm.listen() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            Spacer(Modifier.width(10.dp))
            Action(
                label = if (q.thinking) copy.reading else copy.check,
                filled = true,
                enabled = q.text.isNotBlank() && !q.thinking,
                modifier = Modifier.weight(1f),
            ) {
                keyboard?.hide()
                vm.ask()
            }
        }

        StatusLine(q, copy)

        if (q.outcome == null && !q.thinking) {
            Openers(copy.openers, onPick = vm::onText)
        }

        Output(vm, q, lang, copy, speaking)
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun StatusLine(q: Query, copy: Copy) {
    val line = when {
        q.notice != null -> q.notice
        q.thinking -> copy.reading
        q.millis > 0 -> copy.readIn(q.millis)
        else -> ""
    }
    Text(
        line,
        fontSize = 11.5.sp, lineHeight = 17.sp,
        color = if (q.notice != null) Ink.warn else Ink.t4,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun Openers(openers: List<String>, onPick: (String) -> Unit) {
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        openers.forEach { opener ->
            Text(
                opener,
                fontSize = 12.5.sp, lineHeight = 19.sp, color = Ink.t3,
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, Ink.line, RoundedCornerShape(9.dp))
                    .clickable(role = Role.Button) { onPick(opener) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun Output(vm: CheckpointViewModel, q: Query, lang: Lang, copy: Copy, speaking: String?) {
    val pack = vm.claims ?: return
    when (val outcome = q.outcome) {
        null -> Unit

        // An essential gap outranks any answer we think we have. One question, nothing else.
        is Outcome.Ask -> Column(Modifier.padding(top = 16.dp)) {
            AskCard(
                outcome, lang, copy,
                onAnswer = vm::answer, onSkip = vm::skip, onBail = vm::bail,
                speakingId = speaking, onSpeak = vm::speak,
            )
        }

        is Outcome.Answer -> Column(
            Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val near = outcome.matches.any { it.near }
            val note = when {
                near -> copy.nearNote
                outcome.matches.size > 1 -> copy.multi(outcome.matches.size)
                else -> null
            }
            note?.let { Text(it, fontSize = 11.5.sp, lineHeight = 18.sp, color = Ink.t4) }
            outcome.matches.forEach { ClaimCard(it, pack, lang, copy, speaking, vm::speak) }
            SituationPanel(vm.questions, outcome.facts, outcome.fired, lang, copy)
        }

        is Outcome.Nothing -> Column(
            Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AbstainCard(
                pack.abstain,
                copy.bestScore((outcome.best * 100).roundToInt()),
                lang,
                copy,
            )
            SituationPanel(vm.questions, outcome.facts, outcome.fired, lang, copy)
        }
    }
}

@Composable
private fun Action(
    label: String,
    filled: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .clip(shape)
            .then(if (filled) Modifier.background(if (enabled) Ink.accent else Ink.raise) else Modifier.border(1.dp, Ink.line, shape))
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = when {
                !enabled -> Ink.t4
                filled -> Color.White
                else -> Ink.t1
            },
        )
    }
}
