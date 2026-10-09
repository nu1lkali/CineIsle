package app.marlboroadvance.mpvex.ui.player.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.marlboroadvance.mpvex.preferences.PlayerButton
import app.marlboroadvance.mpvex.ui.player.Panels
import app.marlboroadvance.mpvex.ui.player.PlayerActivity
import app.marlboroadvance.mpvex.ui.player.PlayerViewModel
import app.marlboroadvance.mpvex.ui.player.Sheets
import app.marlboroadvance.mpvex.ui.player.VideoAspect
import app.marlboroadvance.mpvex.ui.player.controls.components.ControlsButton
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassBorder
import app.marlboroadvance.mpvex.ui.player.controls.components.PlayerControlGlassFill
import app.marlboroadvance.mpvex.ui.theme.controlColor
import app.marlboroadvance.mpvex.ui.theme.spacing
import dev.vivvvek.seeker.Segment

@Composable
fun TopLeftPlayerControlsLandscape(
  mediaTitle: String?,
  hideBackground: Boolean,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  viewModel: PlayerViewModel,
) {
  val playlistModeEnabled = viewModel.hasPlaylistSupport()
  val clickEvent = LocalPlayerButtonsClickEvent.current

  Row(
    modifier = Modifier.width(IntrinsicSize.Max),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    ControlsButton(
      icon = Icons.AutoMirrored.Default.ArrowBack,
      onClick = onBackPress,
      color = controlColor,
      modifier = Modifier.size(45.dp),
    )

    val titleInteractionSource = remember { MutableInteractionSource() }

    Box(
      modifier =
        Modifier
          .height(45.dp)
          .clip(RoundedCornerShape(50))
          .clickable(
            enabled = playlistModeEnabled,
            onClick = {
              clickEvent()
              onOpenSheet(Sheets.Playlist)
            },
          ),
    ) {
      Surface(
        shape = RoundedCornerShape(50),
        color =
          if (hideBackground) {
            Color.Transparent
          } else {
            PlayerControlGlassFill
          },
        contentColor = controlColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border =
          if (hideBackground) {
            null
          } else {
            BorderStroke(
              1.dp,
              PlayerControlGlassBorder,
            )
          },
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
          modifier =
            Modifier.padding(
              horizontal = MaterialTheme.spacing.medium,
              vertical = MaterialTheme.spacing.small,
            ),
        ) {
          viewModel.getPlaylistInfo()?.let { playlistInfo ->
            PlaylistIndexBadge(text = playlistInfo)
            Text(
              text = Typography.bullet.toString(),
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 1,
              color = controlColor,
              overflow = TextOverflow.Clip,
            )
          }
          Text(
            text = mediaTitle ?: "",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = controlColor,
            modifier = Modifier.weight(1f, fill = false),
          )
        }
      }
    }
  }
}

@Composable
fun TopRightPlayerControlsLandscape(
  buttons: List<PlayerButton>,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  hideBackground: Boolean,
  decoder: app.marlboroadvance.mpvex.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
  /** 缂備焦姊归悧鏇㈡儓閸℃ê绶炵€广儱娲﹂弳蹇涙煕濮橆剙顨欑紒鏂跨摠缁鸿棄螖娴ｇ瓔娲梺鍝勬湰閹歌顪冮崒婊呯＞?true闂佹寧绋戦悧鍡樼附閺嶎厼浼犵€广儱顦崝銉╂煕閹烘垶顥為悗鍨矒閺岋箓顢欓悙顒傛殸闂佹悶鍎抽崑鎾绘偉閿濆妫橀柣鐔哄閸婅鈽夐幘鎶筋€楅柡鍕€婚埀顒傛暬濞艰崵妲?*/
  isPortrait: Boolean = false,
  buttonSize: Dp = 45.dp,
  /** 闂傗偓閹稿骸鐦婚柕鍡楀缁楀宕板鍕靛仺闁靛棗绉崯?鐎殿喚鎳撻ˇ顒€鈻旈妸锔绘敱闁挎稒鐩埀顒€绻嬬槐鍓佺磼?[RenderPlayerButton]闁靛棔绯爑ll = 闂傗偓閹稿骸鐦婚柡鍐Т瀵姤鎯旈弬鐑╁亾?*/
  onDislikeRemarkRequest: (() -> Unit)? = null,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    buttons.forEach { button ->
      RenderPlayerButton(
        button = button,
        chapters = chapters,
        currentChapter = currentChapter,
        isPortrait = isPortrait,
        isSpeedNonOne = isSpeedNonOne,
        currentZoom = currentZoom,
        aspect = aspect,
        mediaTitle = mediaTitle,
        hideBackground = hideBackground,
        decoder = decoder,
        playbackSpeed = playbackSpeed,
        onBackPress = onBackPress,
        onOpenSheet = onOpenSheet,
        onOpenPanel = onOpenPanel,
        viewModel = viewModel,
        activity = activity,
        buttonSize = buttonSize,
        onDislikeRemarkRequest = onDislikeRemarkRequest,
      )
    }
  }
}

@Composable
fun BottomRightPlayerControlsLandscape(
  buttons: List<PlayerButton>,
  /** 缂備焦姊归悧鏇㈡儓閸℃ê绶炵€广儱娲﹂弳蹇涙煕濮橆剙顨欑紒鏂款嚟缁辨帡宕熼鍜佸仺闂佹寧绋掑銊╂偟椤愩倓娌煫鍥ㄦ尵濞煎矂鎮楃涵鍛棄鐟滄妸鍥у珘?~411dp闂佹寧绋戦惌鍌氥€掗崜浣瑰暫濞达絿鎳撴儫闂佸湱顭堥ˇ鐢稿箰瀹曞洨纾介柍杞扮閻?42dp 闂佸綊娼х粔鐢稿棘娴ｅ壊鍤楁俊鐐插⒔閻熸挾鈧綊娼荤粻鎴ｃ亹閸涘﹦鈻旈柕鍫濇川濞?*/
  buttonSize: Dp = 45.dp,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  hideBackground: Boolean,
  decoder: app.marlboroadvance.mpvex.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
  onDislikeRemarkRequest: (() -> Unit)? = null,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    buttons.forEach { button ->
      RenderPlayerButton(
        button = button,
        chapters = chapters,
        currentChapter = currentChapter,
        isPortrait = false,
        isSpeedNonOne = isSpeedNonOne,
        currentZoom = currentZoom,
        aspect = aspect,
        mediaTitle = mediaTitle,
        hideBackground = hideBackground,
        decoder = decoder,
        playbackSpeed = playbackSpeed,
        onBackPress = onBackPress,
        onOpenSheet = onOpenSheet,
        onOpenPanel = onOpenPanel,
        viewModel = viewModel,
        activity = activity,
        buttonSize = buttonSize,
        onDislikeRemarkRequest = onDislikeRemarkRequest,
      )
    }
  }
}

@Composable
fun BottomLeftPlayerControlsLandscape(
  buttons: List<PlayerButton>,
  /** 闂?[BottomRightPlayerControlsLandscape]闂佹寧绋戦惉濂告偟椤愩倓娌煫鍥ф捣閻?42dp */
  buttonSize: Dp = 45.dp,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  hideBackground: Boolean,
  decoder: app.marlboroadvance.mpvex.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
  onDislikeRemarkRequest: (() -> Unit)? = null,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
  ) {
    buttons.forEach { button ->
      RenderPlayerButton(
        button = button,
        chapters = chapters,
        currentChapter = currentChapter,
        isPortrait = false,
        isSpeedNonOne = isSpeedNonOne,
        currentZoom = currentZoom,
        aspect = aspect,
        mediaTitle = mediaTitle,
        hideBackground = hideBackground,
        decoder = decoder,
        playbackSpeed = playbackSpeed,
        onBackPress = onBackPress,
        onOpenSheet = onOpenSheet,
        onOpenPanel = onOpenPanel,
        viewModel = viewModel,
        activity = activity,
        buttonSize = buttonSize,
        onDislikeRemarkRequest = onDislikeRemarkRequest,
      )
    }
  }
}

/**
 * 缂備焦姊归悧鏇㈡儓閸℃ɑ鍎熼柡鍥ュ灩閸斻儵鏌熺粙娆炬█闁瑰憡濞婂鑸靛箠闁? *
 * 闂佸搫鍞查崨顓ф毉闂佺娉涢敃銉р偓鍨矒閺岋箓顢欓挊澶樷偓鏇㈡煙鐎涙ê濮傞柍褜鍓欓懟顖涚▕韫囨洝濮抽柛銉簻閸ㄨ京鈧綊娼绘俊鍥╂?+ 闂佸憡鐟ョ壕顓㈡儍閿濆洦瀚婚柡澶嬪灥缁€浣虹磽閸屾胆婊堝焵椤掆偓缁嬪嫮妲愬┑鍥┾枖妞ゆ挶鍨洪敍鐔虹磼鐏炶姤鎯堥柛銈庡幗缁嬪鍩€椤掍礁绶炵憸宥咁焽閿熺姵鏅? * 闁荤喐鐟ュΛ婊堬綖鎼淬垻鈻斿┑鐘插€搁崜濂告偠濮樼厧浜楃紒鏃€鎸冲鍫曞灳閸愯尙鈧鏌涢幘鐟扮毢闁搞倕閰ｅ畷姘跺炊閵娿儳浜ｆ繛鎴炴尪閸婃牠顢欏畝鍕櫖闁割偁鍨洪弳蹇涙煙閺夋垵妲荤€殿喛濮ら敍鍐醇濠靛洨娈ら梺鍝勫閸ㄧ數鎹㈤弽銊р枖妞ゅ繐妫岄弫鍕⒒閸涱喚鎳曠紒杈ㄥ哺婵? * 闂佺粯绮嶅妯猴耿椤忓牆瑙﹂柛顐ｇ箚鐎氭瑩鏌?*婵炴垶鎸撮崑鎾绘煛婢规嚎鍊楃粻鍓х磽娓氬洤骞楅悗鍨矒閺岋箓顢欓懞銉ㄦ嫬濡ょ姷鍋犻崺鏍汲閿濆棙濯撮柟鎯х－濠€宄扳槈?*闂佹寧绋掔喊宥団偓鍨矒閺岋箓顢欓懡銈庡悈闂傚倸鍊瑰娆掋亹瑜斿鍨緞瀹€鈧崺鐘测槈閹绢垰浜鹃梺?4dp 闂傚倸鍊婚ˉ鎰玻濞戙垺鏅? * 婵炴垶鎸哥粔鎾偤閵娾晛鎹堕柕濞垮€涢崢顒勬⒒閸屾稒灏﹂柛瀣嚇閺屽棝骞樼€涙鍔烽柣蹇曞仜閸婂爼鍩€? *
 * 闂佸湱顭堥ˇ鐢稿箰閹惰棄鏋佸ù鍏兼綑濞呫倝鎮洪幒鎴炲殌缂佽鍟粙澶愬焵椤掑倹鍋橀悘鐐电摂閸熷秹骞栨潏楣冩婵＄偛鍊歌灒妞ゅ繐瀚崐缁樼節婵犲啳澹樺┑顔规櫊瀹曟顭ㄩ崘顏冧孩 闂佺偨鍎查弻锟犲焵?婵炴潙鍚嬮敋闁告ɑ绋掔粚鍗炩攽婵犲嫭顫嶉梺闈涙婵傛梻绮径灞惧晳濞达絿顭堥悗濂告煏閸℃洘顦风紒妤€顦甸弻灞筋吋閸涱厾绉鹃梺闈涙缁嬪嫮妲? * 闂佸吋婢樻總鏃傜箔婢舵劕鍙婃い鏍ㄧ☉鎯熼梺鍦焾椤︾敻骞愰幎钘夌闁靛牆鎳愬В鍫ユ煙鐎涙澧虫い鏂哄亾婵炴垶鎸堕崐妤咁敂椤掑倹宕夐柟瀛樼箓閻撳倿鎮橀悙鑼缂佺娀鏀卞鍕冀閵夈垹浜? */
@Composable
fun CenteredBottomPlayerControlsPortrait(
  buttons: List<PlayerButton>,
  buttonSize: Dp = 42.dp,
  chapters: List<Segment>,
  currentChapter: Int?,
  isSpeedNonOne: Boolean,
  currentZoom: Float,
  aspect: VideoAspect,
  mediaTitle: String?,
  hideBackground: Boolean,
  decoder: app.marlboroadvance.mpvex.ui.player.Decoder,
  playbackSpeed: Float,
  onBackPress: () -> Unit,
  onOpenSheet: (Sheets) -> Unit,
  onOpenPanel: (Panels) -> Unit,
  viewModel: PlayerViewModel,
  activity: PlayerActivity,
  /**
   * 长按「不喜欢」按钮 → 请求弹备注框。
   *
   * 竖屏底部这条控件簇同样要能长按，不能只有横屏那几个簇支持 ——
   * 否则按钮在竖屏出现了、长按却没反应，用户会以为坏了。
   */
  onDislikeRemarkRequest: (() -> Unit)? = null,
) {
  Box(
    modifier = Modifier.fillMaxWidth(),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      modifier = Modifier.horizontalScroll(rememberScrollState()),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.extraSmall),
    ) {
      buttons.forEach { button ->
        RenderPlayerButton(
          button = button,
          chapters = chapters,
          currentChapter = currentChapter,
          isPortrait = true,
          isSpeedNonOne = isSpeedNonOne,
          currentZoom = currentZoom,
          aspect = aspect,
          mediaTitle = mediaTitle,
          hideBackground = hideBackground,
          decoder = decoder,
          playbackSpeed = playbackSpeed,
          onBackPress = onBackPress,
          onOpenSheet = onOpenSheet,
          onOpenPanel = onOpenPanel,
          viewModel = viewModel,
          activity = activity,
          buttonSize = buttonSize,
          onDislikeRemarkRequest = onDislikeRemarkRequest,
        )
      }
    }
  }
}


