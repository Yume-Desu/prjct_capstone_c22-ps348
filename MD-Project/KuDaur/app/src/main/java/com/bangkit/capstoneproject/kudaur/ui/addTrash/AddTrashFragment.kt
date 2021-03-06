package com.bangkit.capstoneproject.kudaur.ui.addTrash

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.bangkit.capstoneproject.kudaur.R
import com.bangkit.capstoneproject.kudaur.data.preferences.SessionPreference
import com.bangkit.capstoneproject.kudaur.databinding.FragmentAddTrashBinding
import com.bangkit.capstoneproject.kudaur.ml.ModelClassify
import com.bangkit.capstoneproject.kudaur.utils.createCustomTempFile
import com.bangkit.capstoneproject.kudaur.utils.rotateBitmap
import com.bangkit.capstoneproject.kudaur.utils.uriToFile
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AddTrashFragment : Fragment() {

    private lateinit var binding: FragmentAddTrashBinding
    private lateinit var viewModel: AddTrashViewModel
    private lateinit var session: SessionPreference

    private lateinit var currentPhotoPath: String

    private var getFile: File? = null
    private var imageSize: Int = 300
    private val IMAGE_MEAN = 0
    private val IMAGE_STD = 255.0f
    private val PIXEL_SIZE = 3

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val REQUEST_CODE_PERMISSIONS = 10
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAddTrashBinding.inflate(inflater, container, false)
        return binding.root
    }

    /* permission */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            (requireActivity() as AddTrashActivity).baseContext,
            it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        session = SessionPreference(view.context)
        val token = session.getAuthToken() ?: ""

        viewModel =
            ViewModelProvider(this, AddTrashViewModelFactory(token))[AddTrashViewModel::class.java]
        viewModel.isLoading.observe(viewLifecycleOwner) {
            showLoading(it)
        }

        viewModel.toastText.observe(viewLifecycleOwner) {
            it.getContentIfNotHandled()?.let { toastText ->
                showToast(toastText)
            }
        }
        viewModel.file.observe(viewLifecycleOwner) {
            it?.let {
                getFile = it
                val imageBmp = BitmapFactory.decodeFile(it.path)
                binding.imgTrash.setImageBitmap(imageBmp)

                val image = Bitmap.createScaledBitmap(imageBmp, imageSize, imageSize, false)
                outputGenerator(image)
            }
        }

        /* permission */
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                (requireActivity() as AddTrashActivity),
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        binding.buttonCamera.setOnClickListener { startTakePhoto() }
        binding.buttonGallery.setOnClickListener { startGallery() }
//        binding.buttonAddTrash.setOnClickListener { uploadImage() }
    }

    private fun outputGenerator(bitmap: Bitmap) {

        val model = ModelClassify.newInstance(requireContext())

        // Creates inputs for reference.
        val inputFeature0 =
            TensorBuffer.createFixedSize(intArrayOf(1, 300, 300, 3), DataType.FLOAT32)

        val byteBuffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * PIXEL_SIZE)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(imageSize * imageSize)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0

        for (i in 0 until imageSize) {
            for (j in 0 until imageSize) {
                val input = intValues[pixel++]

                byteBuffer.putFloat((((input.shr(16) and 0xFF) - IMAGE_MEAN) / IMAGE_STD))
                byteBuffer.putFloat((((input.shr(8) and 0xFF) - IMAGE_MEAN) / IMAGE_STD))
                byteBuffer.putFloat((((input and 0xFF) - IMAGE_MEAN) / IMAGE_STD))
            }
        }
        inputFeature0.loadBuffer(byteBuffer)

        // Runs model inference and gets result.
        val outputs = model.process(inputFeature0)
        val outputFeature0 = outputs.outputFeature0AsTensorBuffer

        val confidences: FloatArray = outputFeature0.floatArray
        var maxPos = 0
        var maxConfidence = 0F

        for (i in 0 until confidences.size) {
            if (confidences[i] > maxConfidence) {
                maxConfidence = confidences[i]
                maxPos = i
            }
        }
        val classes = arrayOf(
            getString(R.string.cardboard),
            getString(R.string.glass),
            getString(R.string.metal),
            getString(R.string.organic),
            getString(R.string.paper),
            getString(R.string.plastic)
        )

        binding.tvResult.text = classes[maxPos]
        binding.tvResult.visibility = View.VISIBLE

//        binding.tvResult.text = getString(R.string.metal)

        // Releases model resources if no longer used.
        model.close()
    }

    private fun startGallery() {
        val intent = Intent()
        intent.action = Intent.ACTION_GET_CONTENT
        intent.type = "image/*"
        val chooser = Intent.createChooser(intent, getString(R.string.choose_a_picture))
        launcherIntentGallery.launch(chooser)
    }

    private fun startTakePhoto() {
        view?.let { v ->
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.resolveActivity((requireActivity() as AddTrashActivity).packageManager)

            createCustomTempFile((requireActivity() as AddTrashActivity).application).also {
                val photoURI: Uri = FileProvider.getUriForFile(
                    v.context,
                    "com.bangkit.capstoneproject.kudaur",
                    it
                )
                currentPhotoPath = it.absolutePath
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                launcherIntentCamera.launch(intent)
            }
        }
    }

    private val launcherIntentGallery = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            val selectedImg: Uri = result.data?.data as Uri
            view?.let { uriToFile(selectedImg, it.context) }?.also {
                viewModel.setFile(it)
            }
        }
    }

    private val launcherIntentCamera = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == AppCompatActivity.RESULT_OK) {

            val myFile = File(currentPhotoPath)

            val result = rotateBitmap(
                BitmapFactory.decodeFile(myFile.path),
                true
            )

            result.compress(Bitmap.CompressFormat.JPEG, 100, FileOutputStream(myFile))
            viewModel.setFile(myFile)
        }
    }

    private fun uploadImage() {
        TODO("Not yet implemented")
    }

    private fun showToast(toastText: String) {
        Toast.makeText(view?.context, toastText, Toast.LENGTH_SHORT).show()
    }

    private fun showLoading(isLoading: Boolean) {
        if (isLoading) {
            binding.buttonCamera.isEnabled = false
            binding.buttonGallery.isEnabled = false
            binding.buttonAddTrash.isEnabled = false
            binding.edtDescription.isEnabled = false
            binding.pbAddStory.visibility = View.VISIBLE
        } else {
            binding.buttonCamera.isEnabled = true
            binding.buttonGallery.isEnabled = true
            binding.buttonAddTrash.isEnabled = true
            binding.edtDescription.isEnabled = true
            binding.pbAddStory.visibility = View.GONE
        }
    }
}