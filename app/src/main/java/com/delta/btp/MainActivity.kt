package com.delta.btp

import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zebra.sdk.comm.BluetoothConnection
import com.zebra.sdk.comm.Connection
import com.zebra.sdk.comm.ConnectionException
import com.zebra.sdk.comm.TcpConnection
import com.zebra.sdk.printer.PrinterLanguage
import com.zebra.sdk.printer.SGD
import com.zebra.sdk.printer.ZebraPrinter
import com.zebra.sdk.printer.ZebraPrinterFactory
import com.zebra.sdk.printer.ZebraPrinterLanguageUnknownException


class MainActivity : AppCompatActivity() {
    private var connection: Connection? = null
    private lateinit var btRadioButton: RadioButton
    private lateinit var macAddressEditText: EditText
    private lateinit var ipAddressEditText: EditText
    private lateinit var portNumberEditText: EditText
    private val bluetoothAddressKey = "ZEBRA_DEMO_BLUETOOTH_ADDRESS"
    private val tcpAddressKey = "ZEBRA_DEMO_TCP_ADDRESS"
    private val tcpPortKey = "ZEBRA_DEMO_TCP_PORT"
    private val PREFS_NAME = "OurSavedAddress"

    private lateinit var testButton: Button
    private var printer: ZebraPrinter? = null
    private var statusField: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        handleUI()
    }
    private fun handleUI(){
        val settings = getSharedPreferences(PREFS_NAME, 0)

        ipAddressEditText = findViewById(R.id.ipAddressInput)
        val ip = settings.getString(tcpAddressKey, "")
        ipAddressEditText.setText(ip)

        portNumberEditText = findViewById(R.id.portInput)
        val port = settings.getString(tcpPortKey, "")
        portNumberEditText.setText(port)

        macAddressEditText = findViewById(R.id.macInput)
        val mac = settings.getString(bluetoothAddressKey, "AC:3F:A4:F0:94:3B")
        macAddressEditText.setText(mac)


        statusField = findViewById(R.id.statusText)


        btRadioButton = findViewById(R.id.bluetoothRadio)


        val radioGroup = findViewById<RadioGroup>(R.id.radioGroup)
        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            if (checkedId == R.id.bluetoothRadio) {
                toggleEditField(macAddressEditText, true)
                toggleEditField(portNumberEditText, false)
                toggleEditField(ipAddressEditText, false)
            } else {
                toggleEditField(portNumberEditText, true)
                toggleEditField(ipAddressEditText, true)
                toggleEditField(macAddressEditText, false)
            }
        }

        testButton = findViewById(R.id.testButton)
        testButton.setOnClickListener(View.OnClickListener {
            Thread {
                Log.e("click", "trigger test")
                enableTestButton(true)
                Looper.prepare()
                doConnectionTest()
                Looper.loop()
                Looper.myLooper()!!.quit()
            }.start()
        })
    }

    fun connect(): ZebraPrinter? {
        setStatus("Connecting...", Color.YELLOW)
        connection = null
        if (isBluetoothSelected()) {
            connection = BluetoothConnection(getMacAddressFieldText())
            SettingsHelper.saveBluetoothAddress(this, getMacAddressFieldText())
        } else {
            try {
                val port: Int = getTcpPortNumber()!!.toInt()
                connection = TcpConnection(getTcpAddress(), port)
                SettingsHelper.saveIp(this, getTcpAddress())
                SettingsHelper.savePort(this, getTcpPortNumber())
            } catch (e: NumberFormatException) {
                setStatus("Port Number Is Invalid", Color.RED)
                return null
            }
        }
        try {
            connection!!.open()
            setStatus("Connected", Color.GREEN)
        } catch (e: ConnectionException) {
            setStatus("Comm Error! Disconnecting", Color.RED)
            Log.e("zebra-error", "114" + e.message ?: "")
            Thread.sleep(1000)
            disconnect()
        }
        var printer: ZebraPrinter? = null
        if (connection!!.isConnected()) {
            try {
                printer = ZebraPrinterFactory.getInstance(connection)
                setStatus("Determining Printer Language", Color.YELLOW)
                val pl = SGD.GET("device.languages", connection)
                setStatus("Printer Language $pl", Color.BLUE)
            } catch (e: ConnectionException) {
                setStatus("Unknown Printer Language", Color.RED)
                printer = null
                Log.e("zebra-error", "128" + e.message ?: "")
                Thread.sleep(1000)
                disconnect()
            } catch (e: ZebraPrinterLanguageUnknownException) {
                setStatus("Unknown Printer Language", Color.RED)
                Log.e("zebra-error", "133" + e.message ?: "")
                printer = null
                Thread.sleep(1000)
                disconnect()
            }
        }
        return printer
    }
    fun disconnect() {
        try {
            setStatus("Disconnecting", Color.RED)
            if (connection != null) {
                connection!!.close()
            }
            setStatus("Not Connected", Color.RED)
        } catch (e: ConnectionException) {
            setStatus("COMM Error! Disconnected", Color.RED)
        } finally {
            enableTestButton(true)
        }
    }
    private fun sendTestLabel() {
        try {
            Log.d("zebra-sendTestLabel", "try send label")
            val linkOsPrinter = ZebraPrinterFactory.createLinkOsPrinter(printer)
            val printerStatus =
                if (linkOsPrinter != null) linkOsPrinter.currentStatus else printer!!.currentStatus
            Log.d("zebra-sendTestLabel", "try send label")

            if (printerStatus.isReadyToPrint) {
                val configLabel: ByteArray = getConfigLabel()!!
                connection!!.write(configLabel)
                setStatus("Sending Data", Color.BLUE)
            } else if (printerStatus.isHeadOpen) {
                setStatus("Printer Head Open", Color.RED)
            } else if (printerStatus.isPaused) {
                setStatus("Printer is Paused", Color.RED)
            } else if (printerStatus.isPaperOut) {
                setStatus("Printer Media Out", Color.RED)
            }
            Log.d("zebra-sendTestLabel", "isReadyToPrint")

            Thread.sleep(1500)
            if (connection is BluetoothConnection) {
                Log.d("zebra-sendTestLabel", "BluetoothConnection")

                val friendlyName = (connection as BluetoothConnection).friendlyName
                setStatus(friendlyName, Color.MAGENTA)
                Thread.sleep(500)
            }
        } catch (e: ConnectionException) {
            setStatus(e.message!!, Color.RED)
        } finally {
            Log.e("zebra-error", "179")
            disconnect()
        }
    }

    private fun getConfigLabel(): ByteArray? {
        var configLabel: ByteArray? = null
        try {
            val printerLanguage = printer!!.printerControlLanguage
            SGD.SET("device.languages", "zpl", connection)
            if (printerLanguage == PrinterLanguage.ZPL) {

                //para configurar un formato de impresion diseñarlo en la siguiente pagina http://labelary.com/viewer.html
                val bytes =
                    "^XA^FX Top section with company logo, name and address.^CF0,60^FO50,50^GB100,100,100^FS^FO75,75^FR^GB100,100,100^FS^FO88,88^GB50,50,50^FS^FO220,50^FDIntershipping, Inc.^FS^CF0,30^FO220,115^FD1000 Shipping Lane^FS^FO220,155^FDShelbyville TN 38102^FS^FO220,195^FDUnited States (USA)^FS^FO50,250^GB700,1,3^FS^FX Second section with recipient address and permit information.^CFA,30^FO50,300^FDJohn Doe^FS^FO50,340^FD100 Main Street^FS^FO50,380^FDSpringfield TN 39021^FS^FO50,420^FDUnited States (USA)^FS^CFA,15^FO600,300^GB150,150,3^FS^FO638,340^FDPermit^FS^FO638,390^FD123456^FS^FO50,500^GB700,1,3^FS^FX Third section with barcode.^BY5,2,270^FO100,550^BC^FD12345678^FS^FX Fourth section (the two boxes on the bottom).^FO50,900^GB700,250,3^FS^FO400,900^GB1,250,3^FS^CF0,40^FO100,960^FDCtr. X34B-1^FS^FO100,1010^FDREF1 F00B47^FS^FO100,1060^FDREF2 BL4H8^FS^CF0,190^FO470,955^FDCA^FS^XZ"
                configLabel = bytes.toByteArray()
            } else if (printerLanguage == PrinterLanguage.CPCL) {
                val cpclConfigLabel = """
                ! 0 200 200 406 1
                ON-FEED IGNORE
                BOX 20 20 380 380 8
                T 0 6 137 177 TEST
                PRINT
                
                """.trimIndent()
                configLabel = cpclConfigLabel.toByteArray()
            }
        } catch (e: ConnectionException) {
            Log.e("ConectionExeption", e.message + " " + e.cause)
        }
        return configLabel
    }

    private fun setStatus(statusMessage: String, color: Int) {
        runOnUiThread {
            statusField!!.setBackgroundColor(color)
            statusField!!.text = statusMessage
        }
        Thread.sleep(1000)
    }

    private fun doConnectionTest() {
        Log.e("zebra-error", "start test")
        printer = connect()
        if (printer != null) {
            Log.e("zebra-error", "before test")
            sendTestLabel()
        } else {
            Log.e("zebra-error", "226")
            disconnect()
        }
    }

    private fun enableTestButton(enabled: Boolean) {
        runOnUiThread { testButton!!.isEnabled = true }
    }

    private fun toggleEditField(editText: EditText, set: Boolean) {
        editText.isEnabled = set
        editText.isFocusable = set
        editText.isFocusableInTouchMode = set
    }
    private fun isBluetoothSelected(): Boolean {
        return btRadioButton.isChecked
    }
    private fun getMacAddressFieldText(): String? {
        return macAddressEditText.text.toString()
    }

    private fun getTcpAddress(): String? {
        return ipAddressEditText.text.toString()
    }

    private fun getTcpPortNumber(): String? {
        return portNumberEditText.text.toString()
    }

}