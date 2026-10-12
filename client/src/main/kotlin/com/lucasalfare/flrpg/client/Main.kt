package com.lucasalfare.flrpg.client

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

private data class TerminalTheme(
  val background: Color,
  val foreground: Color,
  val muted: Color,
  val accent: Color,
  val border: Color,
)

private val darkTheme = TerminalTheme(
  background = Color(0xFF111318), foreground = Color(0xFFD7DCE5),
  muted = Color(0xFF778195), accent = Color(0xFF80C8A0), border = Color(0xFF394252),
)
private val lightTheme = TerminalTheme(
  background = Color(0xFFF4F3EE), foreground = Color(0xFF282B32),
  muted = Color(0xFF74777D), accent = Color(0xFF356C53), border = Color(0xFFB9B8B0),
)

private enum class Screen(val label: String) {
  GAME("JOGO"), MENU("MENU"), INVENTORY("INVENTÁRIO"), EQUIPMENT("EQUIPAMENTOS"),
  ACTIVITIES("ATIVIDADES"), DISCOVERIES("DESCOBERTAS"), PLAYERS("JOGADORES PRÓXIMOS"),
  COMBAT("COMBATE · DEMONSTRAÇÃO"), TRADE("NEGOCIAÇÃO · DEMONSTRAÇÃO"), TRAVEL("VIAGEM · DEMONSTRAÇÃO"),
}

private data class LogLine(val time: String, val text: String)

private class DemoSession {
  var screen by mutableStateOf(Screen.GAME)
  var dark by mutableStateOf(true)
  var input by mutableStateOf("")
  private val commandHistory = mutableStateListOf<String>()
  private var historyIndex by mutableStateOf<Int?>(null)
  private var inputBeforeHistory by mutableStateOf("")
  var coins by mutableStateOf(320)
  var potions by mutableStateOf(3)
  var route by mutableStateOf(0)
  var location by mutableStateOf("Posto de exploração · Blumenau")
  var activity by mutableStateOf("Nenhuma atividade em andamento")
  val log = mutableStateListOf(
    LogLine("09:42", "Chegaste ao posto de exploração."),
    LogLine("09:38", "Consultaste o mapa da região."),
    LogLine("09:31", "Recebeste 20 moedas por uma tarefa."),
  )

  fun run(raw: String) {
    val command = raw.trim()
    if (command.isEmpty()) return
    rememberCommand(command)
    val upper = command.uppercase()
    when {
      screen == Screen.MENU && upper in listOf("T", "TEMA") -> {
        dark = !dark
        add("Tema alterado para ${if (dark) "escuro" else "claro"}.")
      }
      upper in listOf("M", "MENU", "AJUDA") -> screen = Screen.MENU
      upper in listOf("V", "VOLTAR", "JOGO", "ESC") -> screen = Screen.GAME
      upper in listOf("I", "INVENTARIO", "INVENTÁRIO") -> screen = Screen.INVENTORY
      upper in listOf("E", "EQUIPAMENTO", "EQUIPAMENTOS") -> screen = Screen.EQUIPMENT
      upper in listOf("A", "ATIVIDADES", "STATUS") -> screen = Screen.ACTIVITIES
      upper in listOf("D", "DESCOBERTAS", "OBJETOS", "L") -> screen = Screen.DISCOVERIES
      upper in listOf("J", "JOGADORES") -> screen = Screen.PLAYERS
      upper == "C" || upper.startsWith("ATACAR") || upper == "COMBATE" -> screen = Screen.COMBAT
      upper == "N" || upper.startsWith("NEGOCIAR") || upper == "NEGOCIAÇÃO" -> screen = Screen.TRADE
      upper == "VJ" || upper.startsWith("VIAJAR") || upper == "VIAGEM" -> screen = Screen.TRAVEL
      upper.startsWith("IR") -> {
        screen = Screen.ACTIVITIES
        route = 0
        activity = "Deslocamento · Saída para as ruínas (#12) · 0/3 trechos"
        add("Iniciaste um deslocamento fictício para as ruínas.")
      }
      upper == "AVANÇAR" || upper == "AVANCAR" -> {
        if (activity.startsWith("Deslocamento")) {
          route = (route + 1).coerceAtMost(3)
          if (route == 3) {
            location = "Entrada das ruínas antigas"
            activity = "Nenhuma atividade em andamento"
            add("Chegaste à entrada das ruínas (demonstração local).")
          } else activity = "Deslocamento · Saída para as ruínas (#12) · $route/3 trechos"
          screen = Screen.ACTIVITIES
        } else add("Não há atividade de demonstração para avançar.")
      }
      upper.startsWith("EXAMINAR") -> { screen = Screen.DISCOVERIES; add("Detalhes de demonstração para: ${command.substringAfter(' ', "objeto")}.") }
      upper.startsWith("PEGAR") || upper.startsWith("COLETAR") -> {
        coins += 1
        screen = Screen.INVENTORY
        add("Uma moeda fictícia foi adicionada ao inventário.")
      }
      upper.startsWith("USAR") -> {
        if (potions > 0) potions--
        screen = Screen.INVENTORY
        add("Uso simulado localmente; nenhuma regra do servidor foi executada.")
      }
      else -> add("Comando não reconhecido: ‘$command’. Digite M para abrir o menu.")
    }
    input = ""
  }

  fun updateInput(value: String) {
    input = value
    historyIndex = null
    inputBeforeHistory = ""
  }

  fun previousCommand(): Boolean {
    if (commandHistory.isEmpty()) return false

    val nextIndex = historyIndex?.let { (it - 1).coerceAtLeast(0) } ?: run {
      inputBeforeHistory = input
      commandHistory.lastIndex
    }
    historyIndex = nextIndex
    input = commandHistory[nextIndex]
    return true
  }

  fun nextCommand(): Boolean {
    val currentIndex = historyIndex ?: return false
    if (currentIndex < commandHistory.lastIndex) {
      val nextIndex = currentIndex + 1
      historyIndex = nextIndex
      input = commandHistory[nextIndex]
    } else {
      historyIndex = null
      input = inputBeforeHistory
      inputBeforeHistory = ""
    }
    return true
  }

  private fun rememberCommand(command: String) {
    if (commandHistory.size == 20) commandHistory.removeAt(0)
    commandHistory.add(command)
    historyIndex = null
    inputBeforeHistory = ""
  }

  private fun add(message: String) { log.add(0, LogLine("agora", message)) }
}

private val mono = FontFamily.Monospace

fun main() = application {
  Window(onCloseRequest = ::exitApplication, title = "FL-RPG · terminal") {
    val session = remember { DemoSession() }
    val theme = if (session.dark) darkTheme else lightTheme
    TerminalApp(session, theme)
  }
}

@Composable
private fun TerminalApp(session: DemoSession, theme: TerminalTheme) {
  Column(
    Modifier.fillMaxSize().background(theme.background).padding(horizontal = 18.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    SelectionContainer {
      Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("FL-RPG", color = theme.accent, fontSize = 18.sp, fontFamily = mono, fontWeight = FontWeight.Bold)
          Text("  ·  cliente textual  ·  sessão local", color = theme.muted, fontSize = 13.sp, fontFamily = mono)
          Spacer(Modifier.weight(1f))
          Text("[ ${if (session.dark) "DARK" else "LIGHT"} ]  [ DEMO ]", color = theme.muted, fontSize = 12.sp, fontFamily = mono)
        }
        Text("${"─".repeat(100)}", color = theme.border, fontSize = 12.sp, fontFamily = mono, maxLines = 1)
      }
    }

    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      TerminalTile(
        title = "${session.location.uppercase()}  /  ${session.screen.label}",
        theme = theme,
        modifier = Modifier.weight(2.2f).fillMaxHeight(),
      ) {
        MainContent(session, theme)
      }
      Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TerminalTile("PERSONAGEM", theme, Modifier.weight(1f)) {
          SelectionContainer {
            TextBlock(theme, "Viajante veterano\nNível 18 · 2.450 XP\nVida    [████████░░] 85/100\nMoedas  ${session.coins}\n\n${session.activity}")
          }
        }
        TerminalTile("HISTÓRICO RECENTE", theme, Modifier.weight(1f)) {
          Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            session.log.forEach { TextBlock(theme, "${it.time}  ${it.text}", small = true) }
          }
        }
      }
    }

    SelectionContainer {
      Row(
        Modifier.fillMaxWidth().height(42.dp).border(1.dp, theme.border).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(":", color = theme.accent, fontSize = 15.sp, fontFamily = mono)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
          value = session.input,
          onValueChange = session::updateInput,
          modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
              Key.DirectionUp -> session.previousCommand()
              Key.DirectionDown -> session.nextCommand()
              else -> false
            }
          }.onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
              session.run(session.input)
              true
            } else false
          }.focusable(),
          singleLine = true,
          textStyle = TextStyle(color = theme.foreground, fontFamily = mono, fontSize = 14.sp),
          cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.accent),
          decorationBox = { inner ->
            Box {
              if (session.input.isEmpty()) Text("digite comando · M menu · Enter executar", color = theme.muted, fontFamily = mono, fontSize = 14.sp)
              inner()
            }
          },
        )
      }
    }
  }
}

@Composable
private fun MainContent(session: DemoSession, theme: TerminalTheme) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(11.dp)) {
    when (session.screen) {
      Screen.MENU -> {
        TextBlock(theme, "MENU DE JOGO", heading = true)
        TextBlock(theme, "[ I ] Inventário       [ E ] Equipamentos\n[ A ] Atividades       [ D ] Descobertas\n[ J ] Jogadores        [ C ] Combate (demo)\n[ N ] Negociação (demo) [ VJ ] Viagem (demo)\n[ V ] Voltar ao jogo")
        TextBlock(theme, "APARÊNCIA", heading = true)
        TextBlock(theme, "[ T ] Alternar tema: ${if (session.dark) "escuro" else "claro"}\nPara mudar, digite T enquanto este menu estiver aberto.", color = theme.accent)
      }
      Screen.GAME -> {
        TextBlock(theme, "Posto de exploração", heading = true)
        TextBlock(theme, "Localização atual · Blumenau\nHora do mundo · indisponível nesta demonstração")
        TerminalTile("OBJETOS E PESSOAS POR PERTO", theme) {
          TextBlock(theme, "#12  Saída para as ruínas antigas   · saída disponível\n#18  Comerciante                   · NPC\n#19  Terminal de viagens           · interagível\n#27  Nara                           · jogadora próxima\n#31  Moeda comum                    · visível, coletável")
        }
        TerminalTile("COMANDOS DE EXEMPLO", theme) {
          TextBlock(theme, "M menu · I inventário · STATUS atividades · D descobertas\nIR #12 inicia deslocamento demo · AVANÇAR percorre trecho demo")
        }
        TextBlock(theme, "Dados fictícios para validar a organização da tela; sem conexão com o servidor.", theme.muted, small = true)
      }
      Screen.INVENTORY -> TextBlock(theme, "INVENTÁRIO\n\nITENS CARREGADOS\nPoção de cura             × ${session.potions}\nEspada curta              × 1\nMinério de ferro           × 8\nRelíquia antiga            × 2\nMoeda comum                × ${session.coins}\n\n‘USAR poção’ altera apenas esta demonstração.")
      Screen.EQUIPMENT -> TextBlock(theme, "EQUIPAMENTOS\n\nArma       Espada de aço\nArmadura   Cota de malha\nAcessório  Anel de proteção\nEscudo     — (Escudo de madeira na mochila)\n\nSlots ilustrativos; a estrutura final acompanhará o domínio.")
      Screen.ACTIVITIES -> TextBlock(theme, "ATIVIDADE PRINCIPAL\n\n${session.activity}\n${if (session.route > 0) "\n${"■".repeat(session.route)}${"□".repeat(3 - session.route)}  ${session.route}/3 trechos\nDigite AVANÇAR para simular o próximo trecho." else ""}\n\nConsultar status não avança o tempo do mundo.", theme.foreground)
      Screen.DISCOVERIES -> TextBlock(theme, "OBJETOS DISPONÍVEIS\n\n#12  Saída para as ruínas antigas\n#18  Comerciante\n#19  Terminal de viagens\n#31  Moeda comum · visível · coletável\n\nExistência, percepção e possibilidade de interação são informações distintas.")
      Screen.PLAYERS -> TextBlock(theme, "JOGADORES PRÓXIMOS\n\n#27  Nara · junto à banca · visível\n\nEstado fictício local. Ações pessoais não são eventos globais.")
      Screen.COMBAT -> TextBlock(theme, "ENCONTRO · Esqueleto errante #42\n\nINIMIGO  [██████░░░░] 60/60\nVOCÊ     [████████░░] 85/100\nArma: Espada de aço · Armadura: Cota de malha\n\nATACAR #42 · DEFENDER · USAR poção · FUGIR\nNenhum dano ou resultado real é calculado aqui.")
      Screen.TRADE -> TextBlock(theme, "NEGOCIAÇÃO · Comerciante #18\n\nDisponível: Escudo de madeira · Poção de cura\nRecursos: ${session.coins} moedas · 8 minérios\nProposta atual: nenhuma\n\nApresentação simulada; sem preço ou aceitação implementados.")
      Screen.TRAVEL -> TextBlock(theme, "TERMINAL DE VIAGENS #19\n\nDestino de exemplo: Florianópolis\nDuração estimada: 3 horas (fictícia)\n\nConclusões reais deverão vir da autoridade do jogo.")
    }
  }
}

@Composable
private fun TerminalTile(
  title: String,
  theme: TerminalTheme,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Column(
    modifier.border(1.dp, theme.border).padding(horizontal = 10.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    SelectionContainer {
      Text("┌─ $title", color = theme.accent, fontFamily = mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    SelectionContainer {
      Box(Modifier.fillMaxWidth().weight(1f, fill = false)) { content() }
    }
  }
}

@Composable
private fun TextBlock(
  theme: TerminalTheme,
  text: String,
  color: Color = theme.foreground,
  heading: Boolean = false,
  small: Boolean = false,
) {
  Text(
    text = text,
    color = color,
    fontFamily = mono,
    fontSize = when { small -> 12.sp; heading -> 19.sp; else -> 14.sp },
    fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal,
    lineHeight = if (small) 17.sp else 22.sp,
  )
}
