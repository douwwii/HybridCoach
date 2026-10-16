package fr.hybridcoach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.hybridcoach.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: CoachViewModel =
                viewModel(
                    factory =
                        androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(application)
                )
            HybridApp(vm)
        }
    }
}
