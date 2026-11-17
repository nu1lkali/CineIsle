package app.marlboroadvance.mpvex.di

import app.marlboroadvance.mpvex.domain.anime4k.Anime4KManager
import app.marlboroadvance.mpvex.domain.smartmerge.SmartMergeManager
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext

val domainModule = module {
    single { Anime4KManager(androidContext()) }
    single { SmartMergeManager(get(), androidContext()) }
}
