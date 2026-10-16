package fr.hybridcoach

import android.app.Application
import androidx.room.Room
import fr.hybridcoach.data.*

class CoachApplication : Application() {
    val repository by lazy {
        CoachRepository(
            Room.databaseBuilder(this, CoachDatabase::class.java, "hybrid-coach.db").build()
        )
    }
}
