package dev.surzhykyvych

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class CleaningClassifier(context: Context) {

    private var interpreter: Interpreter? = null

    private val timeSteps = 100
    private val numFeatures = 6
    private val numClasses = 5

    private val means = floatArrayOf(0.147f, 9.977f, 0.141f, 0.145f, 0.095f, 0.096f)
    private val stds = floatArrayOf(1.901f, 2.486f, 1.903f, 1.791f, 1.714f, 1.789f)

    val classNames: Array<String> = context.resources.getStringArray(R.array.activity_classes)

    init {
        val modelBuffer = loadModelFile(context)
        interpreter = Interpreter(modelBuffer, Interpreter.Options())
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd("cleaning_model.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    fun classify(window: List<FloatArray>): FloatArray {
        val inputBuffer = ByteBuffer.allocateDirect(1 * timeSteps * numFeatures * 4)
        inputBuffer.order(ByteOrder.nativeOrder())

        for (sample in window) {
            for (i in 0 until numFeatures) {
                inputBuffer.putFloat((sample[i] - means[i]) / stds[i])
            }
        }

        inputBuffer.rewind()

        val outputBuffer = Array(1) { FloatArray(numClasses) }
        interpreter?.run(inputBuffer, outputBuffer)

        return outputBuffer[0]
    }

    fun close() {
        interpreter?.close()
    }
}