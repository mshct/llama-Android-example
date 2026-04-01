package com.example.llama

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // setup view elements
    private lateinit var setupView: View
    private lateinit var backBar: View
    private lateinit var btnBackToChat: View
    private lateinit var btnUnloadModel: View
    private lateinit var btnSelectFile: View
    private lateinit var modelSummarySection: View
    private lateinit var tvModelName: TextView
    private lateinit var tvModelSummary: TextView
    private lateinit var tvShowMetadata: TextView
    private lateinit var configCard: View
    private lateinit var etGpuLayers: EditText
    private lateinit var sliderGpuLayers: SeekBar
    private lateinit var paramsCard: View
    private lateinit var etTemperature: EditText
    private lateinit var etMaxTokens: EditText
    private lateinit var etUbatch: EditText
    private lateinit var btnLoadModel: View
    private lateinit var loadingSection: View
    private lateinit var tvLoadingStatus: TextView

    // chat view elements
    private lateinit var chatView: View
    private lateinit var tvChatInfo: TextView
    private lateinit var btnReconfigure: View
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var sendFab: FloatingActionButton
    private lateinit var btnStop: TextView

    // inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null

    // conversation state
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    // selection state
    private var selectedUri: Uri? = null
    private var parsedMetadata: GgufMetadata? = null
    private var fullMetadataString: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback { Log.w(TAG, "back press ignored") }

        // setup view
        setupView = findViewById(R.id.setup_view)
        backBar = findViewById(R.id.back_bar)
        btnBackToChat = findViewById(R.id.btn_back_to_chat)
        btnUnloadModel = findViewById(R.id.btn_unload_model)
        btnSelectFile = findViewById(R.id.btn_select_file)
        modelSummarySection = findViewById(R.id.model_summary_section)
        tvModelName = findViewById(R.id.tv_model_name)
        tvModelSummary = findViewById(R.id.tv_model_summary)
        tvShowMetadata = findViewById(R.id.tv_show_metadata)
        configCard = findViewById(R.id.config_card)
        etGpuLayers = findViewById(R.id.et_gpu_layers)
        sliderGpuLayers = findViewById(R.id.slider_gpu_layers)
        paramsCard = findViewById(R.id.params_card)
        etTemperature = findViewById(R.id.et_temperature)
        etMaxTokens = findViewById(R.id.et_max_tokens)
        etUbatch = findViewById(R.id.et_ubatch)
        btnLoadModel = findViewById(R.id.btn_load_model)
        loadingSection = findViewById(R.id.loading_section)
        tvLoadingStatus = findViewById(R.id.tv_loading_status)

        // chat view
        chatView = findViewById(R.id.chat_view)
        tvChatInfo = findViewById(R.id.tv_chat_info)
        btnReconfigure = findViewById(R.id.btn_reconfigure)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        sendFab = findViewById(R.id.fab)
        btnStop = findViewById(R.id.btn_stop)

        // slider ↔ number input two-way binding
        sliderGpuLayers.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) etGpuLayers.setText(progress.toString())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        etGpuLayers.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString()?.toIntOrNull() ?: return
                if (v in 0..sliderGpuLayers.max) sliderGpuLayers.progress = v
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // engine init
        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
        }

        btnStop.setOnClickListener {
            generationJob?.cancel()
        }

        btnSelectFile.setOnClickListener { getContent.launch(arrayOf("*/*")) }

        tvShowMetadata.setOnClickListener {
            parsedMetadata?.let { showMetadataDialog(it) }
        }

        btnLoadModel.setOnClickListener { startLoadFlow() }

        btnBackToChat.setOnClickListener { showChatView() }

        btnUnloadModel.setOnClickListener {
            engine.cleanUp()
            isModelReady = false
            messages.clear()
            messageAdapter.notifyDataSetChanged()
            selectedUri = null
            parsedMetadata = null
            fullMetadataString = null
            modelSummarySection.visibility = View.GONE
            configCard.visibility = View.GONE
            paramsCard.visibility = View.GONE
            btnLoadModel.visibility = View.GONE
            backBar.visibility = View.GONE
            btnSelectFile.isEnabled = true
            btnStop.visibility = View.GONE
            btnStop.text = "■  Stop generation"
            btnStop.setTextColor(ContextCompat.getColor(this, R.color.accent))
        }

        btnReconfigure.setOnClickListener {
            backBar.visibility = View.VISIBLE
            if (isModelReady) btnLoadModel.visibility = View.VISIBLE
            showSetupView()
        }

        sendFab.setOnClickListener { handleUserInput() }
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleSelectedModel(it) }
    }

    private fun handleSelectedModel(uri: Uri) {
        selectedUri = uri
        btnSelectFile.isEnabled = false
        tvModelName.text = "Parsing..."
        modelSummarySection.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val metadata = try {
                contentResolver.openInputStream(uri)?.use {
                    GgufMetadataReader.create().readStructuredMetadata(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse GGUF metadata", e)
                null
            }

            if (metadata == null) {
                withContext(Dispatchers.Main) {
                    btnSelectFile.isEnabled = true
                    modelSummarySection.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Not a valid GGUF file", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            parsedMetadata = metadata
            fullMetadataString = metadata.toString()

            withContext(Dispatchers.Main) {
                btnSelectFile.isEnabled = true

                val baseName = metadata.basic.name ?: metadata.architecture?.architecture ?: "Unknown model"
                val sizeLabel = metadata.basic.sizeLabel
                val nameSpan = SpannableStringBuilder(baseName)
                if (sizeLabel != null) {
                    nameSpan.append("  $sizeLabel")
                    nameSpan.setSpan(
                        ForegroundColorSpan(0xFF888888.toInt()),
                        baseName.length, nameSpan.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                tvModelName.text = nameSpan

                val summary = buildList {
                    metadata.dimensions?.blockCount?.let { add("$it layers") }
                    metadata.dimensions?.contextLength?.let { add("ctx $it") }
                    metadata.tensorCount.let { add("$it tensors") }
                }.joinToString(", ")
                tvModelSummary.text = summary

                // set slider range from model layer count
                val blockCount = metadata.dimensions?.blockCount ?: 0
                sliderGpuLayers.max = blockCount
                sliderGpuLayers.progress = 0

                configCard.visibility = View.VISIBLE
                paramsCard.visibility = View.VISIBLE
                btnLoadModel.visibility = View.VISIBLE
            }
        }
    }

    private fun startLoadFlow() {
        val nGpuLayers = etGpuLayers.text.toString().toIntOrNull() ?: 0
        val nUbatch = etUbatch.text.toString().toIntOrNull() ?: InferenceEngine.DEFAULT_UBATCH
        val uri = selectedUri ?: return
        val metadata = parsedMetadata ?: return

        btnLoadModel.visibility = View.GONE
        btnSelectFile.isEnabled = false
        loadingSection.visibility = View.VISIBLE

        val modelName = metadata.filename() + FILE_EXTENSION_GGUF

        lifecycleScope.launch(Dispatchers.IO) {
            if (isModelReady) {
                withContext(Dispatchers.Main) { tvLoadingStatus.text = "Unloading previous model..." }
                engine.cleanUp()
                withContext(Dispatchers.Main) { isModelReady = false }
            }

            withContext(Dispatchers.Main) { tvLoadingStatus.text = "Copying model file..." }

            val modelFile = contentResolver.openInputStream(uri)?.use { inputStream ->
                ensureModelFile(modelName, inputStream)
            } ?: run {
                Log.e(TAG, "Failed to open input stream")
                withContext(Dispatchers.Main) {
                    loadingSection.visibility = View.GONE
                    btnLoadModel.visibility = View.VISIBLE
                    btnSelectFile.isEnabled = true
                    Toast.makeText(this@MainActivity, "Failed to read model file", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) { tvLoadingStatus.text = "Loading model (${if (nGpuLayers == 0) "CPU" else "GPU $nGpuLayers layers"})..." }

            engine.loadModel(modelFile.path, nGpuLayers, nUbatch)

            withContext(Dispatchers.Main) {
                isModelReady = true
                val mode = if (nGpuLayers == 0) "CPU only" else "GPU ($nGpuLayers layers)"
                tvChatInfo.text = "${metadata.basic.name ?: "Model"} · $mode"
                userInputEt.isEnabled = true
                loadingSection.visibility = View.GONE
                showChatView()
            }
        }
    }

    private fun showSetupView() {
        setupView.visibility = View.VISIBLE
        chatView.visibility = View.GONE
    }

    private fun showChatView() {
        setupView.visibility = View.GONE
        chatView.visibility = View.VISIBLE
    }

    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                if (!file.exists()) {
                    Log.i(TAG, "Copying model to $modelName")
                    FileOutputStream(file).use { input.copyTo(it) }
                } else {
                    Log.i(TAG, "Model already exists: $modelName")
                }
            }
        }

    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Input is empty", Toast.LENGTH_SHORT).show()
                return
            }

            val temp = etTemperature.text.toString().toFloatOrNull() ?: InferenceEngine.DEFAULT_SAMPLER_TEMP
            val maxTokens = etMaxTokens.text.toString().toIntOrNull() ?: InferenceEngine.DEFAULT_PREDICT_LENGTH

            var startTime = 0L
            var lastUpdateTime = 0L
            var tokenCount = 0
            var prefillTime = 0L

            userInputEt.text = null
            userInputEt.isEnabled = false
            sendFab.isEnabled = false
            btnStop.text = "■  Stop generation"
            btnStop.setTextColor(ContextCompat.getColor(this, R.color.accent))
            btnStop.visibility = View.VISIBLE

            val userMessage = Message(UUID.randomUUID().toString(), userMsg, true)
            val assistantMessage = Message(UUID.randomUUID().toString(), "", false)
            messages.add(userMessage)
            messages.add(assistantMessage)
            messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
            messagesRv.scrollToPosition(messages.size - 1)

            generationJob = lifecycleScope.launch(Dispatchers.Default) {
                startTime = System.currentTimeMillis()

                engine.sendUserPrompt(userMsg, maxTokens, temp)
                    .onCompletion { cause ->
                        val wasCancelled = cause is CancellationException
                        // use lifecycleScope.launch — withContext won't run inside a cancelled job
                        lifecycleScope.launch(Dispatchers.Main) {
                            userInputEt.isEnabled = true
                            sendFab.isEnabled = true
                            if (wasCancelled) {
                                btnStop.text = "◼  Generation stopped"
                                btnStop.setTextColor(0xFFFF8888.toInt())
                            } else {
                                btnStop.visibility = View.GONE
                            }
                            lastAssistantMsg.clear()
                            val totalMs = System.currentTimeMillis() - startTime
                            val speed = if (tokenCount > 0) tokenCount / (totalMs / 1000.0) else 0.0
                            assistantMessage.prefillMs = prefillTime
                            assistantMessage.totalMs = totalMs
                            assistantMessage.tokenCount = tokenCount
                            assistantMessage.tokensPerSec = speed
                            messageAdapter.notifyItemChanged(messages.indexOf(assistantMessage))
                            Log.i(TAG, "done: $tokenCount tokens, ${totalMs}ms, ${"%.2f".format(speed)} t/s")
                        }
                    }
                    .collect { token ->
                        tokenCount++
                        lastAssistantMsg.append(token)
                        if (tokenCount == 1) {
                            prefillTime = System.currentTimeMillis() - startTime
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime > 100 || tokenCount == 1) {
                            withContext(Dispatchers.Main) {
                                assistantMessage.content = lastAssistantMsg.toString()
                                messageAdapter.notifyItemChanged(messages.size - 1)
                            }
                            lastUpdateTime = now
                        }
                    }
            }
        }
    }

    private fun showMetadataDialog(metadata: GgufMetadata) {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val keyColor = 0xFF888888.toInt()
        val valColor = 0xFFDDDDDD.toInt()

        val ssb = SpannableStringBuilder()

        fun section(name: String) {
            val start = ssb.length
            ssb.append("[$name]\n")
            ssb.setSpan(ForegroundColorSpan(accent), start, ssb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, ssb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        fun row(key: String, value: Any?) {
            if (value == null) return
            val kStart = ssb.length
            ssb.append("  ${key.padEnd(14)}")
            ssb.setSpan(ForegroundColorSpan(keyColor), kStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val vStart = ssb.length
            ssb.append("$value\n")
            ssb.setSpan(ForegroundColorSpan(valColor), vStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val hStart = ssb.length
        ssb.append("GGUF ${metadata.version}  ·  ${metadata.tensorCount} tensors  ·  ${metadata.kvCount} kv pairs\n\n")
        ssb.setSpan(ForegroundColorSpan(keyColor), hStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        section("General")
        row("name", metadata.basic.name)
        row("size", metadata.basic.sizeLabel)
        row("label", metadata.basic.nameLabel)
        row("uuid", metadata.basic.uuid)
        ssb.append("\n")

        metadata.architecture?.let { arch ->
            section("Architecture")
            row("arch", arch.architecture)
            row("file type", arch.fileType)
            row("vocab size", arch.vocabSize)
            row("finetune", arch.finetune)
            row("quant ver", arch.quantizationVersion)
            ssb.append("\n")
        }

        metadata.dimensions?.let { dim ->
            section("Dimensions")
            row("context", dim.contextLength)
            row("embedding", dim.embeddingSize)
            row("layers", dim.blockCount)
            row("feed fwd", dim.feedForwardSize)
            ssb.append("\n")
        }

        metadata.attention?.let { att ->
            section("Attention")
            row("heads", att.headCount)
            row("kv heads", att.headCountKv)
            row("key length", att.keyLength)
            row("val length", att.valueLength)
            row("rms epsilon", att.layerNormRmsEpsilon)
            ssb.append("\n")
        }

        metadata.rope?.let { r ->
            section("RoPE")
            row("freq base", r.frequencyBase)
            row("dim count", r.dimensionCount)
            row("scaling", r.scalingType)
            ssb.append("\n")
        }

        metadata.tokenizer?.let { tok ->
            section("Tokenizer")
            row("model", tok.model)
            row("bos token", tok.bosTokenId)
            row("eos token", tok.eosTokenId)
            row("pad token", tok.paddingTokenId)
            row("add bos", tok.addBosToken)
            ssb.append("\n")
        }

        metadata.author?.let { auth ->
            if (auth.organization != null || auth.author != null || auth.url != null) {
                section("Author")
                row("org", auth.organization)
                row("author", auth.author)
                row("url", auth.url)
                row("repo", auth.repoUrl)
                ssb.append("\n")
            }
        }

        metadata.additional?.let { add ->
            if (add.type != null || add.tags != null) {
                section("Additional")
                row("type", add.type)
                row("description", add.description)
                row("tags", add.tags?.joinToString(", "))
                row("languages", add.languages?.joinToString(", "))
            }
        }

        val tv = TextView(this).apply {
            text = ssb
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(0xFF121212.toInt())
            setTextColor(valColor)
            setPadding(48, 48, 48, 48)
            textSize = 12f
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Model metadata")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("OK", null)
            .show()
    }

    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    override fun onStop() {
        generationJob?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size -> "$name-$size" } ?: name
        }
    }
    architecture?.architecture != null -> {
        architecture?.architecture?.let { arch ->
            basic.uuid?.let { uuid -> "$arch-$uuid" } ?: "$arch-${System.currentTimeMillis()}"
        }
    }
    else -> "model-${System.currentTimeMillis().toHexString()}"
}
