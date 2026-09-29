package app.marlboroadvance.mpvex.dlna

import org.koin.dsl.module

/** DLNA 投屏模块：提供 [DlnaCastManager] 单例（底层封装 UPnPCast）。 */
val dlnaModule = module {
  single { DlnaCastManager(get()) }
}
