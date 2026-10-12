package com.vocab.idea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service
@State(name = "VocabSidecarSettings", storages = [Storage("vocab-sidecar.xml")])
class VocabSettings : PersistentStateComponent<VocabSettings.State> {

    class State {
        var dailyNewWords: Int = 10

        /** Appearance of the tool window, editable in Settings > Tools > vocabulary-learning. */
        var fontSize: Int = 14

        /** "#5A5A5A" means "follow the button colour"; see Vocabulary.spellingColor. */
        var spellingColor: String = "#5A5A5A"
        var meaningColor: String = "#1A1A1A"
        var sentenceColor: String = "#6A6A6A"
        var mnemonicColor: String = "#8A6D3B"
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(s: State) { myState = s }

    companion object {
        val instance: VocabSettings
            get() = ApplicationManager.getApplication().getService(VocabSettings::class.java)
    }
}
