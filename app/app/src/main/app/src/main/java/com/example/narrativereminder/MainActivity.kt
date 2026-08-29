package com.example.narrativereminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val narrativeScript: String,
    val targetTimeMillis: Long,
    val categoryId: Long,
    val isCompleted: Boolean = false
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM categories")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity)

    @Query("SELECT * FROM reminders ORDER BY targetTimeMillis ASC")
    fun getAllReminders(): Flow<List<ReminderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity): Long

    @Update
    suspend fun updateReminder(reminder: ReminderEntity)

    @Delete
    suspend fun deleteReminder(reminder: ReminderEntity)
}

@Database(entities = [CategoryEntity::class, ReminderEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "narrative_reminder_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class NarrativeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val narrative = intent.getStringExtra("NARRATIVE_TEXT") 
            ?: "Attention! You have an unfulfilled reminder right now!"

        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.1f)
                tts?.setPitch(1.3f)
                val textToSpeak = "Attention! $narrative. Repeat: $narrative."
                tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "ALARM_TTS_ID")
            }
        }
    }
}

object AlarmScheduler {
    fun schedule(context: Context, reminder: ReminderEntity) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NarrativeAlarmReceiver::class.java).apply {
            putExtra("NARRATIVE_TEXT", reminder.narrativeScript)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminder.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.targetTimeMillis,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                reminder.targetTimeMillis,
                pendingIntent
            )
        }
    }

    fun cancel(context: Context, reminderId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NarrativeAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}

class MainViewModel(private val dao: ReminderDao) : ViewModel() {
    val categories = dao.getAllCategories()
    val reminders = dao.getAllReminders()

    fun addCategory(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) dao.insertCategory(CategoryEntity(name = name))
    }

    fun addReminder(context: Context, title: String, narrative: String, timeMillis: Long, categoryId: Long) {
        viewModelScope.launch {
            val newReminder = ReminderEntity(
                title = title,
                narrativeScript = narrative,
                targetTimeMillis = timeMillis,
                categoryId = categoryId
            )
            val id = dao.insertReminder(newReminder)
            AlarmScheduler.schedule(context, newReminder.copy(id = id))
        }
    }

    fun toggleComplete(context: Context, reminder: ReminderEntity) = viewModelScope.launch {
        val updated = reminder.copy(isCompleted = !reminder.isCompleted)
        dao.updateReminder(updated)
        if (updated.isCompleted) {
            AlarmScheduler.cancel(context, reminder.id)
        } else {
            AlarmScheduler.schedule(context, updated)
        }
    }

    fun deleteReminder(context: Context, reminder: ReminderEntity) = viewModelScope.launch {
        dao.deleteReminder(reminder)
        AlarmScheduler.cancel(context, reminder.id)
    }
}

class MainViewModelFactory(private val dao: ReminderDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(dao) as T
    }
}

val PureBlack = Color(0xFF000000)
val PureWhite = Color(0xFFFFFFFF)
val DarkGray = Color(0xFF1E1E1E)
val LightGray = Color(0xFF888888)

val MonochromeColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = PureBlack,
    background = PureBlack,
    onBackground = PureWhite,
    surface = DarkGray,
    onSurface = PureWhite
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = AppDatabase.getDatabase(this)
        val viewModelFactory = MainViewModelFactory(db.reminderDao())

        setContent {
            MaterialTheme(colorScheme = MonochromeColorScheme) {
                val viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = viewModelFactory)
                MainScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val categories by viewModel.categories.collectAsState(initial = emptyList())
    val reminders by viewModel.reminders.collectAsState(initial = emptyList())

    var selectedCategoryId by remember { mutableStateOf<Long?>(null) }
    var showAddModal by remember { mutableStateOf(false) }
    var showAddCategoryModal by remember { mutableStateOf(false) }

    LaunchedEffect(categories) {
        if (categories.isEmpty()) {
            viewModel.addCategory("Work")
            viewModel.addCategory("Family")
            viewModel.addCategory("Friends")
            viewModel.addCategory("Travel")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("REMIND.IO", fontWeight = FontWeight.Black, letterSpacing = 2.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PureBlack, titleContentColor = PureWhite)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddModal = true },
                containerColor = PureWhite,
                contentColor = PureBlack
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Reminder")
            }
        },
        containerColor = PureBlack
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedCategoryId == null,
                            onClick = { selectedCategoryId = null },
                            label = { Text("ALL") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PureWhite,
                                selectedLabelColor = PureBlack,
                                containerColor = DarkGray,
                                labelColor = PureWhite
                            )
                        )
                    }
                    items(categories) { cat ->
                        FilterChip(
                            selected = selectedCategoryId == cat.id,
                            onClick = { selectedCategoryId = cat.id },
                            label = { Text(cat.name.uppercase()) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PureWhite,
                                selectedLabelColor = PureBlack,
                                containerColor = DarkGray,
                                labelColor = PureWhite
                            )
                        )
                    }
                }
                IconButton(onClick = { showAddCategoryModal = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Category", tint = PureWhite)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val filteredReminders = if (selectedCategoryId == null) {
                reminders
            } else {
                reminders.filter { it.categoryId == selectedCategoryId }
            }

            if (filteredReminders.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("NO REMINDERS FOUND", color = LightGray, fontWeight = FontWeight.Bold)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(filteredReminders, key = { it.id }) { reminder ->
                        ReminderCard(
                            reminder = reminder,
                            onToggleComplete = { viewModel.toggleComplete(context, reminder) },
                            onDelete = { viewModel.deleteReminder(context, reminder) }
                        )
                    }
                }
            }
        }
    }

    if (showAddModal) {
        AddReminderDialog(
            categories = categories,
            onDismiss = { showAddModal = false },
            onSave = { title, narrative, time, catId ->
                viewModel.addReminder(context, title, narrative, time, catId)
                showAddModal = false
            }
        )
    }

    if (showAddCategoryModal) {
        AddCategoryDialog(
            onDismiss = { showAddCategoryModal = false },
            onSave = { name ->
                viewModel.addCategory(name)
                showAddCategoryModal = false
            }
        )
    }
}

@Composable
fun ReminderCard(
    reminder: ReminderEntity,
    onToggleComplete: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault())
    val formattedDate = dateFormat.format(Date(reminder.targetTimeMillis))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, PureWhite), shape = RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = PureBlack)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = reminder.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = PureWhite,
                    textDecoration = if (reminder.isCompleted) TextDecoration.LineThrough else TextDecoration.None
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "\"${reminder.narrativeScript}\"",
                    fontSize = 14.sp,
                    color = LightGray
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formattedDate,
                    fontSize = 12.sp,
                    color = PureWhite,
                    fontWeight = FontWeight.SemiBold
                )
            }
            IconButton(onClick = onToggleComplete) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Complete",
                    tint = if (reminder.isCompleted) PureWhite else LightGray
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = PureWhite)
            }
        }
    }
}

@Composable
fun AddReminderDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (title: String, narrative: String, timeMillis: Long, categoryId: Long) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var narrative by remember { mutableStateOf("") }
    var selectedCatId by remember { mutableStateOf(categories.firstOrNull()?.id ?: 0L) }
    var minutesFromNow by remember { mutableStateOf("1") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureBlack,
        title = { Text("NEW NARRATIVE REMINDER", color = PureWhite, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title", color = PureWhite) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PureWhite, unfocusedBorderColor = LightGray)
                )
                OutlinedTextField(
                    value = narrative,
                    onValueChange = { narrative = it },
                    label = { Text("Narrative Script (TTS Sound)", color = PureWhite) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PureWhite, unfocusedBorderColor = LightGray)
                )
                OutlinedTextField(
                    value = minutesFromNow,
                    onValueChange = { minutesFromNow = it },
                    label = { Text("Trigger in (Minutes from now)", color = PureWhite) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PureWhite, unfocusedBorderColor = LightGray)
                )
                Text("Category:", color = PureWhite, fontSize = 12.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(categories) { cat ->
                        FilterChip(
                            selected = selectedCatId == cat.id,
                            onClick = { selectedCatId = cat.id },
                            label = { Text(cat.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PureWhite,
                                selectedLabelColor = PureBlack,
                                containerColor = DarkGray,
                                labelColor = PureWhite
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mins = minutesFromNow.toLongOrNull() ?: 1L
                    val time = System.currentTimeMillis() + (mins * 60 * 1000)
                    if (title.isNotBlank() && narrative.isNotBlank() && selectedCatId != 0L) {
                        onSave(title, narrative, time, selectedCatId)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack)
            ) {
                Text("SET REMINDER")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = PureWhite)
            }
        }
    )
}

@Composable
fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onSave: (name: String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PureBlack,
        title = { Text("NEW CATEGORY", color = PureWhite, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Category Name", color = PureWhite) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PureWhite, unfocusedBorderColor = LightGray)
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) onSave(name)
                },
                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = PureBlack)
            ) {
                Text("ADD")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = PureWhite)
            }
        }
    )
}
