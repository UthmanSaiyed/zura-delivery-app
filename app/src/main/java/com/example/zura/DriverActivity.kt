package com.example.zura

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import java.util.*

/**
 * This class displays the drivers page (dashboard) after successful login.
 *
 * This activity serves as the driver dashboard and provides:
 * - Welcome message with driver's name
 * - Navigation to available orders, delivery history, and profile management
 * - Real-time order notifications for new delivery requests
 * - Logout functionality
 *
 * The activity handles notification channel creation and permission requests
 * for Android 13+ devices.
 */
class DriverActivity : AppCompatActivity() {

    /** Firebase Authentication instance */
    private val auth = FirebaseAuth.getInstance()

    /** Firestore database instance */
    private val db = FirebaseFirestore.getInstance()

    /** Notification channel ID for order alerts */
    private val CHANNEL_ID = "driver_order_alerts"

    /** Set of order IDs that have already been notified */
    private val notifiedOrderIds = mutableSetOf<String>()

    /** Timestamp of when the driver logged in */
    private var loginTime: Date = Date()

    /**
     * Called when the activity is first created.
     * Sets up the UI, loads driver information, and initializes notification systems.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_driver)

        // Handle window insets for edge-to-edge display
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val driverText: TextView = findViewById(R.id.driverText)
        val user = auth.currentUser

        // Load and display driver information
        if (user != null) {
            db.collection("users").document(user.uid).get()
                .addOnSuccessListener { doc ->
                    val name = doc.getString("fullName") ?: "Driver"
                    val firstName = name.split(" ").firstOrNull() ?: name
                    driverText.text = "Welcome back, Driver $firstName"

                    // Check if user is actually a driver
                    val isDriver = doc.getBoolean("isDriver") ?: false

                    if (isDriver) {
                        // Set up notification systems
                        createNotificationChannel()
                        requestNotificationPermission()
                        listenForNewOrders()
                    }
                }
        }

        // Set up navigation buttons
        findViewById<Button>(R.id.availableOrdersBtn).setOnClickListener {
            startActivity(Intent(this, AvailableOrdersActivity::class.java))
        }

        findViewById<Button>(R.id.deliveryHistoryBtn).setOnClickListener {
            startActivity(Intent(this, DeliveryHistoryActivity::class.java))
        }

        findViewById<Button>(R.id.manageProfileBtn).setOnClickListener {
            startActivity(Intent(this, ManageProfileActivity::class.java))
        }

        // Set up logout button with confirmation dialog
        findViewById<Button>(R.id.logoutBtn).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to log out?")
                .setNegativeButton("Yes") { _, _ ->
                    auth.signOut()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
                .setPositiveButton("No", null)
                .show()
        }
    }

    /**
     * Listens for new orders added to Firestore after the driver's login time.
     *
     * This real-time listener:
     * - Checks for newly added orders
     * - Filters out orders that existed before login
     * - Prevents duplicate notifications for the same order
     * - Triggers notifications for new eligible orders
     */
    private fun listenForNewOrders() {
        db.collection("orders")
            .addSnapshotListener { snapshots, error ->
                if (error != null || snapshots == null) return@addSnapshotListener

                for (change in snapshots.documentChanges) {
                    if (change.type == DocumentChange.Type.ADDED) {
                        val doc = change.document
                        val orderId = doc.id

                        // Skip if already notified about this order
                        if (notifiedOrderIds.contains(orderId)) continue

                        val timestamp = doc.getTimestamp("timestamp")?.toDate()
                        // Only notify about orders created after login
                        if (timestamp != null && timestamp.after(loginTime)) {
                            sendNotification(
                                "New Order Available",
                                "A delivery is waiting to be picked up."
                            )
                            notifiedOrderIds.add(orderId)
                        }
                    }
                }
            }
    }

    /**
     * Creates and displays a notification to the driver.
     *
     * @param title The title of the notification
     * @param message The content text of the notification
     */
    private fun sendNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.baseline_credit_card_24)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(
                    this@DriverActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notify(System.currentTimeMillis().toInt(), notification)
            }
        }
    }

    /**
     * Creates the notification channel required for Android 8.0+.
     *
     * This channel is used for all driver order notifications.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Driver Order Alerts"
            val description = "Alerts for new orders in the system"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = description
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Requests notification permission for Android 13+ devices.
     *
     * This is required to display notifications on newer Android versions.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }
    }
}