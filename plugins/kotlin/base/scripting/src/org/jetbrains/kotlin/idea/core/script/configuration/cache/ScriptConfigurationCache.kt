// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.core.script.configuration.cache

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.idea.core.script.configuration.utils.getKtFile
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import java.io.Serializable
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * Cached configurations for the file's specific snapshot state.
 *
 * The writer should put related inputs snapshot for loaded configuration.
 * This would allow making up-to-date checks for existed entry.
 *
 * The configuration may be loaded but not applied. So, it makes
 * sense to do up-to-date check on loaded configuration (not on applied).
 * For those reasons, we are storing both for each file.
 */
interface ScriptConfigurationCache {
    operator fun get(file: VirtualFile): ScriptConfigurationState?

    fun setApplied(file: VirtualFile, configurationSnapshot: ScriptConfigurationSnapshot)
    fun setLoaded(file: VirtualFile, configurationSnapshot: ScriptConfigurationSnapshot)
    fun remove(file: VirtualFile): Boolean

    fun allApplied(): List<Pair<VirtualFile, ScriptCompilationConfigurationWrapper>>
    fun clear()
}

data class ScriptConfigurationState(val applied: ScriptConfigurationSnapshot? = null, val loaded: ScriptConfigurationSnapshot? = null) {
    fun isUpToDate(project: Project, file: VirtualFile, ktFile: KtFile? = null): Boolean {
        return (loaded ?: applied)?.inputs?.isUpToDate(project, file, ktFile) ?: false
    }
}

data class ScriptConfigurationSnapshot(
    val inputs: CachedConfigurationInputs,
    val reports: List<ScriptDiagnostic>,
    val configuration: ScriptCompilationConfigurationWrapper?
)

interface CachedConfigurationInputs: Serializable {
    fun isUpToDate(project: Project, file: VirtualFile, ktFile: KtFile? = null): Boolean

    object UpToDate: CachedConfigurationInputs {
        override fun isUpToDate(project: Project, file: VirtualFile, ktFile: KtFile?): Boolean = true
    }

    data class PsiModificationStamp(
        val fileModificationStamp: Long,
        val psiModificationStamp: Long
    ) : CachedConfigurationInputs {
        override fun isUpToDate(project: Project, file: VirtualFile, ktFile: KtFile?): Boolean =
            get(project, file, ktFile) == this

        companion object {
            fun get(project: Project, file: VirtualFile, ktFile: KtFile?): PsiModificationStamp {
                val actualKtFile = project.getKtFile(file, ktFile)
                return PsiModificationStamp(
                    file.modificationStamp,
                    actualKtFile?.modificationStamp ?: 0
                )
            }
        }
    }

    data class SourceContentsStamp(val source: String) : CachedConfigurationInputs {
        override fun isUpToDate(project: Project, file: VirtualFile, ktFile: KtFile?): Boolean {
            return get(file) == this
        }

        companion object {
            fun get(file: VirtualFile): SourceContentsStamp {
                val text = runReadAction {
                    FileDocumentManager.getInstance().getDocument(file)!!.text
                }

                return SourceContentsStamp(text)
            }
        }
    }
}
