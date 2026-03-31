package com.example.llama

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.floatingactionbutton.FloatingActionButton
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

    // Android views
    private lateinit var ggufTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)
    private var currentGpuLayers : Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // View model boilerplate and state management is out of this basic sample's scope
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        // Find views
        ggufTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)



        // Arm AI Chat initialization
        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
        }

        // Upon CTA button tapped
        userActionFab.setOnClickListener {
            if (isModelReady) {
                // If model is ready, validate input and send to engine
                handleUserInput()
            } else {
                // Otherwise, prompt user to select a GGUF metadata on the device
                getContent.launch(arrayOf("*/*"))
            }
        }
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected file uri:\n $uri")
        uri?.let { handleSelectedModel(it) }
    }

    /**
     * Handles the file Uri from [getContent] result
     */
    private fun handleSelectedModel(uri: Uri) {
        // Update UI states
        userActionFab.isEnabled = false
        userInputEt.hint = "Parsing GGUF..."
        ggufTv.text = "Parsing metadata from selected file \n$uri"

        lifecycleScope.launch(Dispatchers.IO) {
            // Parse GGUF metadata
            val metadata = contentResolver.openInputStream(uri)?.use {
                GgufMetadataReader.create().readStructuredMetadata(it)
            } ?: run {
                Log.e(TAG, "Failed to parse GGUF metadata")
                return@launch
            }

            Log.i(TAG, "GGUF parsed: \n$metadata")

            withContext(Dispatchers.Main) {
                ggufTv.text = metadata.toString()

                // 显示 CPU / GPU 选择对话框
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("选择加载模式")
                    .setMessage("请选择 CPU 或 GPU 模式进行对比测试")
                    .setPositiveButton("CPU Only (n_gpu_layers = 0)") { _, _ ->
                        loadModelWithGpuLayers(uri, metadata, 0)
                    }
                    .setNegativeButton("GPU") { _, _ ->
                        showGpuLayersInputDialog(uri, metadata)
                    }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    private fun showGpuLayersInputDialog(uri: Uri, metadata: GgufMetadata) {
        val inputEditText = EditText(this).apply {
            hint = "请输入 GPU 层数 (0-100, -1 表示全部)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setText("2") // 默认值设为 2
        }

        AlertDialog.Builder(this)
            .setTitle("GPU 层数设置")
            .setMessage("请设置 n_gpu_layers 的值:\n\n- 0: 仅使用 CPU\n- 正数: 指定 GPU 处理的层数\n- -1: 全部加载到 GPU")
            .setView(inputEditText)
            .setPositiveButton("确定") { _, _ ->
                val inputText = inputEditText.text.toString()
                if (inputText.isNotEmpty()) {
                    try {
                        val gpuLayers = inputText.toInt()
                        // 验证输入范围
                        val validGpuLayers = when {
                            gpuLayers < -1 -> {
                                Toast.makeText(this, "层数不能小于 -1，已设置为 -1", Toast.LENGTH_SHORT).show()
                                -1
                            }
                            gpuLayers > 100 -> {
                                Toast.makeText(this, "层数超过建议范围，已设置为 100", Toast.LENGTH_SHORT).show()
                                100
                            }
                            else -> gpuLayers
                        }
                        loadModelWithGpuLayers(uri, metadata, validGpuLayers)
                    } catch (e: NumberFormatException) {
                        Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show()
                        // 重新显示对话框
                        showGpuLayersInputDialog(uri, metadata)
                    }
                } else {
                    Toast.makeText(this, "请输入 GPU 层数", Toast.LENGTH_SHORT).show()
                    // 重新显示对话框
                    showGpuLayersInputDialog(uri, metadata)
                }
            }
            .setNegativeButton("取消") { _, _ ->
                // 用户取消，重新显示选择对话框
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("选择加载模式")
                    .setMessage("请选择 CPU 或 GPU 模式进行对比测试")
                    .setPositiveButton("CPU Only (n_gpu_layers = 0)") { _, _ ->
                        loadModelWithGpuLayers(uri, metadata, 0)
                    }
                    .setNegativeButton("GPU (自定义层数)") { _, _ ->
                        showGpuLayersInputDialog(uri, metadata)
                    }
                    .setCancelable(false)
                    .show()
            }
            .show()
    }
    /**
     * 根据用户选择的 GPU layers 加载模型
     */
    private fun loadModelWithGpuLayers(uri: Uri, metadata: GgufMetadata, nGpuLayers: Int) {
        val modelName = metadata.filename() + FILE_EXTENSION_GGUF

        lifecycleScope.launch(Dispatchers.IO) {
            // 重新打开 InputStream 用于拷贝文件（关键点！）
            val modelFile = contentResolver.openInputStream(uri)?.use { inputStream ->
                ensureModelFile(modelName, inputStream)
            } ?: run {
                Log.e(TAG, "Failed to open input stream for model file")
                return@launch
            }

            Log.i(TAG, "Start loading model with n_gpu_layers = $nGpuLayers")

            // 真正加载模型，使用用户选择的 GPU 参数
            loadModel(modelName, modelFile, nGpuLayers)

            withContext(Dispatchers.Main) {
                isModelReady = true
                currentGpuLayers = nGpuLayers

                userInputEt.hint = "Type and send a message! (GPU layers = $nGpuLayers)"
                userInputEt.isEnabled = true
                userActionFab.setImageResource(R.drawable.outline_send_24)
                userActionFab.isEnabled = true

                ggufTv.append("\n\n当前加载模式: ${if (nGpuLayers == 0) "CPU Only" else "GPU ($nGpuLayers layers)"}")
            }
        }
    }
    /**
     * Prepare the model file within app's private storage
     */
    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                // Copy the file into local storage if not yet done
                if (!file.exists()) {
                    Log.i(TAG, "Start copying file to $modelName")
                    withContext(Dispatchers.Main) {
                        userInputEt.hint = "Copying file..."
                    }

                    FileOutputStream(file).use { input.copyTo(it) }
                    Log.i(TAG, "Finished copying file to $modelName")
                } else {
                    Log.i(TAG, "File already exists $modelName")
                }
            }
        }

    /**
     * Load the model file from the app private storage
     */
    private suspend fun loadModel(
        modelName: String,
        modelFile: File,
        nGpuLayers: Int = 0          // ← 新增默认参数
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Loading model $modelName | GPU layers = $nGpuLayers (0=CPU, -1=Full GPU)")
        withContext(Dispatchers.Main) {
            userInputEt.hint = "Loading model... (GPU=$nGpuLayers)"
        }

        // 调用引擎
        engine.loadModel(modelFile.path, nGpuLayers)
    }

    /**
     * Validate and send the user message into [InferenceEngine]
     */
    private fun handleUserInput() {
        userInputEt.text.toString().also { userMsg ->
            if (userMsg.isEmpty()) {
                Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            } else {
                var startTime = 0L
                var lastUpdateTime = 0L
                var tokenCount = 0
                userInputEt.text = null
                userInputEt.isEnabled = false
                userActionFab.isEnabled = false

                // 添加用户消息
                messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
                // 添加一个空的助手消息（用于实时更新）
                val assistantMessage = Message(UUID.randomUUID().toString(), "", false)
                messages.add(assistantMessage)

                messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
                messagesRv.scrollToPosition(messages.size - 1)

                generationJob = lifecycleScope.launch(Dispatchers.Default) {
                    startTime = System.currentTimeMillis()
                    var prefillTime: Long = 0

                    engine.sendUserPrompt(userMsg)
                        .onCompletion {
                            withContext(Dispatchers.Main) {
                                userInputEt.isEnabled = true
                                userActionFab.isEnabled = true
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
                                Log.d(TAG, "Prefill Time: $prefillTime ms")
                            }

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime > 100 || tokenCount == 1) { // 限制刷新频率
                                withContext(Dispatchers.Main) {
                                    assistantMessage.content = lastAssistantMsg.toString()
                                    messageAdapter.notifyItemChanged(messages.size - 1)
                                }
                                lastUpdateTime = currentTime
                            }
                        }
                }
            }
        }
    }

    /**
     * Run a benchmark with the model file
     */
    @Deprecated("This benchmark doesn't accurately indicate GUI performance expected by app developers")
    private suspend fun runBenchmark(modelName: String, modelFile: File) =
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Starts benchmarking $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Running benchmark..."
            }
            engine.bench(
                pp=BENCH_PROMPT_PROCESSING_TOKENS,
                tg=BENCH_TOKEN_GENERATION_TOKENS,
                pl=BENCH_SEQUENCE,
                nr=BENCH_REPETITION
            ).let { result ->
                messages.add(Message(UUID.randomUUID().toString(), result, false))
                withContext(Dispatchers.Main) {
                    messageAdapter.notifyItemChanged(messages.size - 1)
                }
            }
        }

    /**
     * Create the `models` directory if not exist.
     */
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

        private const val BENCH_PROMPT_PROCESSING_TOKENS = 512
        private const val BENCH_TOKEN_GENERATION_TOKENS = 128
        private const val BENCH_SEQUENCE = 1
        private const val BENCH_REPETITION = 3
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size ->
                "$name-$size"
            } ?: name
        }
    }
    architecture?.architecture != null -> {
        architecture?.architecture?.let { arch ->
            basic.uuid?.let { uuid ->
                "$arch-$uuid"
            } ?: "$arch-${System.currentTimeMillis()}"
        }
    }
    else -> {
        "model-${System.currentTimeMillis().toHexString()}"
    }
}
