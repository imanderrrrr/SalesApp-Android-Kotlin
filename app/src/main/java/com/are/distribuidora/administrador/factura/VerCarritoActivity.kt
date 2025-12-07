package com.are.distribuidora.administrador.factura

import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.are.distribuidora.R
import com.are.distribuidora.administrador.InicioAdmin
import com.are.distribuidora.basedatos.AppDatabase
import com.are.distribuidora.basedatos.Cliente
import com.are.distribuidora.basedatos.DetallePedidoEntity
import com.are.distribuidora.basedatos.PedidoEntity
import com.are.distribuidora.basedatos.Producto
import com.are.distribuidora.workers.ProductoSyncWorker
import com.are.distribuidora.workers.PedidoSyncWorker
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import androidx.lifecycle.lifecycleScope
import com.are.distribuidora.data.repositories.FirestoreUsuariosRepository
import com.are.distribuidora.data.repositories.FirestoreProductosRepository
import com.are.distribuidora.data.repositories.UsuariosRepository
import com.are.distribuidora.data.repositories.ProductosRepository
class VerCarritoActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CarritoAdapter
    private lateinit var textTotal: TextView
    private lateinit var textCliente: TextView

    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private var nombreUsuario: String = "Cargando..."
    private lateinit var usuariosRepository: UsuariosRepository
    private lateinit var productosRepository: ProductosRepository

    private var botonEditarBounds: RectF? = null
    private var botonEliminarBounds: RectF? = null
    private var swipedPosition = -1
    private var swipeOffset = 0f
    private var editarPedidoId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ver_carrito)
        findViewById<Button>(R.id.btnAgregarPersonalizado).setOnClickListener {
            mostrarDialogoProductoManual()
        }

        editarPedidoId = intent.getIntExtra("editarPedidoId", -1)
        if (editarPedidoId == -1 && Carrito.items.isEmpty()) {
            ClienteSeleccionado.cliente = null
        }

        val clienteDesdeIntent = intent.getSerializableExtra("clienteSeleccionado") as? Cliente
        val rutaSeleccionada = intent.getStringExtra("rutaSeleccionada") ?: "Ruta desconocida"
        if (rutaSeleccionada.isNotEmpty()) {
            Log.d("VerCarrito", "Ruta seleccionada: $rutaSeleccionada")
        } else {
            Log.e("VerCarrito", "Ruta no seleccionada o vacía")
        }
        if (clienteDesdeIntent != null) {
            ClienteSeleccionado.cliente = clienteDesdeIntent
            android.util.Log.d("VerCarrito", "Cliente recibido: ${clienteDesdeIntent.nombre}")
        } else {
            android.util.Log.e("VerCarrito", "No se recibió cliente por intent")
        }

        recyclerView = findViewById(R.id.recyclerCarrito)
        textTotal = findViewById(R.id.textTotal)
        textCliente = findViewById(R.id.textCliente)
        mostrarNombreCliente()



        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = CarritoAdapter(
            onEditar = { uiItem, pos ->
                // Map UI item back to domain item by position
                val item = Carrito.items[pos]
                mostrarDialogoEditar(item, pos)
            },
            onEliminar = { pos -> eliminarItem(pos) }
        )
        recyclerView.adapter = adapter
        adapter.submitList(mapUi(Carrito.items))

        findViewById<TextView>(R.id.btnLimpiar).setOnClickListener { confirmarLimpiarCarrito() }

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        usuariosRepository = FirestoreUsuariosRepository(firestore)
        productosRepository = FirestoreProductosRepository(firestore)

        val uid = auth.currentUser?.uid
        val btnConfirmar = findViewById<TextView>(R.id.btnConfirmar)

        // Obtener información del usuario de la sesión local
        val prefs = getSharedPreferences("sesion_usuario", MODE_PRIVATE)
        val usuarioId = prefs.getInt("usuario_id", 0)
        nombreUsuario = prefs.getString("usuario_nombre", null) ?: ""
        
        // Si no hay sesión local, intentar con Firebase (compatibilidad)
        if (nombreUsuario.isEmpty()) {
            btnConfirmar.isEnabled = false
            if (uid != null) {
                lifecycleScope.launch {
                    try {
                        nombreUsuario = usuariosRepository.getUsuarioNombre(uid)
                        Log.d("VerCarrito", " Usuario Firebase: $nombreUsuario")
                    } catch (e: Exception) {
                        Log.e("VerCarrito", " Error al obtener nombre de usuario", e)
                        nombreUsuario = "ErrorUsuario"
                    } finally {
                        btnConfirmar.isEnabled = true
                    }
                }
            } else {
                Log.e("VerCarrito", " Usuario no autenticado")
                nombreUsuario = "SinSesion"
                btnConfirmar.isEnabled = true
            }
        } else {
            Log.d("VerCarrito", " Usuario local: $nombreUsuario (ID: $usuarioId)")
            btnConfirmar.isEnabled = true
        }

        findViewById<TextView>(R.id.btnConfirmar).setOnClickListener {
            val btn = findViewById<TextView>(R.id.btnConfirmar)
            btn.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                // ID del pedido para sincronización en finally
                var pedidoIdForSync: Long = -1
                try {
                    Log.d("VerCarritoActivity", " Entró al try")

                    // Función para verificar conexión
                    fun hayConexionInternet(): Boolean {
                        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                        val redActiva = cm.activeNetworkInfo
                        return redActiva != null && redActiva.isConnected
                    }

                    val hayInternet = hayConexionInternet()
                    val db = AppDatabase.getDatabase(this@VerCarritoActivity)
                    val dao = db.productoDao()
                    val firestore = if (hayInternet) FirebaseFirestore.getInstance() else null
                    val pedidoDao = db.pedidoDao()

                    val clienteNombre = ClienteSeleccionado.cliente?.nombre ?: "Sin cliente"
                    val fechaActual = java.time.LocalDateTime.now().toString()
                    val totalPedidoBruto = Carrito.items.sumOf { (it.producto.precio * it.cantidad) - it.descuento }
                    val totalPedido = redondearAGuatemalteco(totalPedidoBruto)

                    val detallesAnteriores = if (editarPedidoId != -1) {
                        pedidoDao.obtenerDetallesDePedido(editarPedidoId)
                    } else emptyList()

                    val mapaAnteriores = detallesAnteriores.associateBy { it.productoId }
                    
                    // Obtener ID del usuario actual
                    val prefs = getSharedPreferences("sesion_usuario", MODE_PRIVATE)
                    val usuarioId = prefs.getInt("usuario_id", 0)

                    val pedidoId: Long = if (editarPedidoId != -1) {
                        // Preservar idFirestore del pedido original para actualizar el MISMO documento
                        val pedidoPrevio = pedidoDao.obtenerPedidoPorId(editarPedidoId)
                        val pedidoActualizado = PedidoEntity(
                            id = editarPedidoId,
                            clienteNombre = clienteNombre,
                            fecha = fechaActual,
                            total = totalPedido,
                            ruta = rutaSeleccionada,
                            usuario = nombreUsuario,
                            usuarioId = usuarioId,
                            idFirestore = pedidoPrevio?.idFirestore ?: "",
                            sincronizado = false
                        )

                        pedidoDao.insertarPedido(pedidoActualizado)
                        editarPedidoId.toLong()
                    } else {
                        val nuevoPedido = PedidoEntity(
                            clienteNombre = clienteNombre,
                            fecha = fechaActual,
                            total = totalPedido,
                            ruta = rutaSeleccionada,
                            usuario = nombreUsuario,
                            usuarioId = usuarioId,
                            idFirestore = "",
                            sincronizado = false
                        )

                        pedidoDao.insertarPedido(nuevoPedido)
                    }
                    // Guardar para sincronización en finally
                    pedidoIdForSync = pedidoId

                    val detallesNuevos = Carrito.items.filter { item ->
                        detallesAnteriores.none {
                            it.productoId == item.producto.id && it.nombreProducto == item.producto.nombre && it.detalles == item.detalles
                        }
                    }.map {
                        DetallePedidoEntity(
                            pedidoId = pedidoId.toInt(),
                            productoId = it.producto.id,
                            nombreProducto = it.producto.nombre,
                            cantidad = it.cantidad,
                            precioUnitario = it.producto.precio,
                            detalles = it.detalles,
                            descuento = it.descuento 
                        )
                    }


                    if (detallesNuevos.isNotEmpty()) {
                        pedidoDao.insertarDetalles(detallesNuevos)
                    }

                    val productosActuales = Carrito.items.associateBy { it.producto.id }

                    for ((productoId, item) in productosActuales) {
                        val cantidadAnterior = mapaAnteriores[productoId]?.cantidad ?: 0
                        val diferencia = item.cantidad - cantidadAnterior

                        Log.d("VerCarritoActivity", " Producto ID: $productoId, diferencia: $diferencia")

                        if (diferencia != 0) {
                            val productoBD = dao.buscarProductoPorId(productoId)
                            if (productoBD != null) {
                                val nuevoStock = productoBD.stock - diferencia
                                val actualizado = productoBD.copy(stock = nuevoStock, pendienteSync = true)
                                dao.actualizarProducto(actualizado)

                                Log.d(
                                    "VerCarritoActivity",
                                    " Producto actualizado localmente: ${actualizado.nombre}, Stock: ${actualizado.stock}, pendienteSync: ${actualizado.pendienteSync}"
                                )

                                if (hayInternet && firestore != null) {
                                    try {
                                        withTimeout(3000) {
                                            productosRepository.updateProductoStockByClaveGlobal(actualizado.claveGlobal.toString(), nuevoStock)
                                        }
                                        dao.actualizarProducto(actualizado.copy(pendienteSync = false))
                                        Log.i("VerCarritoActivity", " Firestore actualizado: ${actualizado.nombre}, nuevo stock: $nuevoStock")
                                    } catch (e: Exception) {
                                        Log.w("VerCarritoActivity", " No se pudo actualizar Firestore modificado: ${actualizado.nombre} → ${e.message}")
                                    }
                                } else {
                                    Log.d("VerCarritoActivity", " Sin conexión: stock pendiente de sincronizar (${actualizado.nombre})")
                                }
                            }

                        }
                    }

                    val idsActuales = productosActuales.keys
                    for ((productoId, detalleAnterior) in mapaAnteriores) {
                        if (productoId !in idsActuales) {
                            val productoBD = dao.buscarProductoPorId(productoId)
                            if (productoBD != null) {
                                val nuevoStock = productoBD.stock + detalleAnterior.cantidad
                                val actualizado = productoBD.copy(stock = nuevoStock, pendienteSync = true)
                                dao.actualizarProducto(actualizado)

                                if (hayInternet && firestore != null) {
                                    try {
                                        withTimeout(3000) {
                                            productosRepository.updateProductoStockByClaveGlobal(actualizado.claveGlobal.toString(), nuevoStock)
                                        }
                                        dao.actualizarProducto(actualizado.copy(pendienteSync = false))
                                    } catch (e: Exception) {
                                        Log.w("VerCarritoActivity", " No se pudo actualizar Firestore eliminado: ${actualizado.nombre} → ${e.message}")
                                    }
                                }
                            }
                        }
                    }

                    val ventaDao = db.ventaDao()
                    val productosVendidos = Carrito.items.joinToString("\n") {
                        "${it.producto.nombre} x${it.cantidad} = Q${"%.2f".format(it.producto.precio * it.cantidad - it.descuento)}"
                    }

                    val venta = com.are.distribuidora.basedatos.Venta(
                        fecha = fechaActual,
                        cliente = clienteNombre,
                        productosVendidos = productosVendidos,
                        total = totalPedido,
                        pendiente = true
                    )

                    ventaDao.insertarVenta(venta)

                } catch (e: Exception) {
                    Log.e("VerCarritoActivity", " ERROR global: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@VerCarritoActivity, " Error al guardar el pedido", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    Log.d("VerCarritoActivity", " Entró al finally")

                    runOnUiThread {
                        Log.d("VerCarritoActivity", " Ejecutando runOnUiThread")

                        btn.isEnabled = true

                        if (Carrito.items.isNotEmpty()) {
                            Carrito.items.clear()
                            Log.d("VerCarritoActivity", " Carrito limpiado")
                        } else {
                            Log.d("VerCarritoActivity", " Carrito ya estaba vacío")
                        }

                        Toast.makeText(this@VerCarritoActivity, " Pedido procesado. Redirigiendo...", Toast.LENGTH_LONG).show()
                        Log.d("VerCarritoActivity", "➡ Preparando Intent de salida...")

                        //  Programar sincronización de productos pendientes cuando haya red
                        val constraint = Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()

                        val syncRequest = OneTimeWorkRequestBuilder<ProductoSyncWorker>()
                            .setConstraints(constraint)
                            .addTag("sincronizar_stock_pendiente")
                            .build()

                        WorkManager.getInstance(this@VerCarritoActivity).enqueue(syncRequest)
                        Log.d("VerCarritoActivity", "🛰 ProductoSyncWorker programado con constraint de red")

                        //  Programar sincronización de pedidos (subir/actualizar detalles en el mismo documento)
                        if (pedidoIdForSync != -1L) {
                            val pedidoInput = androidx.work.Data.Builder()
                                .putLong("pedidoId", pedidoIdForSync)
                                .build()

                            val pedidoSync = OneTimeWorkRequestBuilder<PedidoSyncWorker>()
                                .setConstraints(constraint)
                                .setInputData(pedidoInput)
                                .addTag("sincronizar_pedido")
                                .build()
                            WorkManager.getInstance(this@VerCarritoActivity).enqueue(pedidoSync)
                            Log.d("VerCarritoActivity", "🛰 PedidoSyncWorker programado para pedidoId=" + pedidoIdForSync)
                        }

                        val destino = if (editarPedidoId != -1) {
                            Intent(this@VerCarritoActivity, DetallePedidoActivity::class.java).apply {
                                putExtra("pedidoId", editarPedidoId)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        } else {
                            Intent(this@VerCarritoActivity, InicioAdmin::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        }

                        Log.d("VerCarritoActivity", " Lanzando startActivity()")
                        startActivity(destino)

                        Log.d("VerCarritoActivity", " Llamando finish()")
                        finish()
                    }
                }

            }
        }








        configurarSwipe()
        actualizarTotal()
    }

    private fun mostrarNombreCliente() {
        val nombre = ClienteSeleccionado.cliente?.nombre ?: "No seleccionado"
        textCliente.text = "Cliente: $nombre"
    }


    private fun configurarSwipe() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val item = Carrito.items[position]

                val opciones = arrayOf("Cambiar cantidad", "Eliminar", "Aplicar descuento", "Editar detalles")
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this@VerCarritoActivity)
                    .setTitle("Acciones del producto")
                    .setItems(opciones) { _, which ->
                        when (which) {
                            0 -> mostrarDialogoEditar(item, position)
                            1 -> eliminarItem(position)
                            2 -> mostrarDialogoDescuento(item, position)
                            3 -> mostrarDialogoEditarDetalles(item, position)
                        }
                    }
                    .setOnDismissListener {
                        adapter.notifyItemChanged(position)
                    }
                    .create()

                dialog.window?.attributes?.windowAnimations = R.style.DialogAnimation_Modern
                dialog.show()
            }
        })

        itemTouchHelper.attachToRecyclerView(recyclerView)



        recyclerView.setOnTouchListener { _, event ->
            if (swipedPosition != -1 && event.action == MotionEvent.ACTION_UP) {
                val x = event.x
                val y = event.y
                val view = recyclerView.findChildViewUnder(x, y)
                val holder = view?.let { recyclerView.getChildViewHolder(it) }

                if (holder != null && holder.adapterPosition == swipedPosition) {
                    if (botonEditarBounds?.contains(x, y) == true) {
                        mostrarDialogoEditar(Carrito.items[swipedPosition], swipedPosition)
                        limpiarSwipe()
                        return@setOnTouchListener true
                    }

                    if (botonEliminarBounds?.contains(x, y) == true) {
                        eliminarItem(swipedPosition)
                        limpiarSwipe()
                        return@setOnTouchListener true
                    }
                }

                // Si no tocó ningún botón, reiniciar swipe
                adapter.notifyItemChanged(swipedPosition)
                swipedPosition = -1
                swipeOffset = 0f
            }
            false
        }
    }


    private fun mostrarDialogoDescuento(item: ItemCarrito, position: Int) {
        val input = EditText(this).apply {
            hint = "Descuento en Q"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val subtotal = item.producto.precio * item.cantidad

        AlertDialog.Builder(this)
            .setTitle("Aplicar descuento")
            .setMessage("Subtotal actual: Q${"%.2f".format(subtotal)}")
            .setView(input)
            .setPositiveButton("Aplicar") { _, _ ->
                val descuento = input.text.toString().toDoubleOrNull()
                if (descuento != null && descuento >= 0 && descuento <= subtotal) {
                    val itemActualizado = item.copy(descuento = descuento)
                    Carrito.items[position] = itemActualizado
                    adapter.submitList(mapUi(Carrito.items))
                    actualizarTotal()

                    Toast.makeText(this, "Descuento aplicado", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Descuento inválido", Toast.LENGTH_SHORT).show()
                    adapter.notifyItemChanged(position)
                }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                adapter.notifyItemChanged(position)
            }
            .show()
    }

    private fun mostrarDialogoEditarDetalles(item: ItemCarrito, position: Int) {
        val input = EditText(this).apply {
            hint = "Nuevo detalle"
            setText(item.detalles)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }

        AlertDialog.Builder(this)
            .setTitle("Editar detalles")
            .setView(input)
            .setPositiveButton("Actualizar") { _, _ ->
                val nuevoDetalle = input.text.toString().trim()
                val itemActualizado = item.copy(detalles = nuevoDetalle)
                Carrito.items[position] = itemActualizado
                adapter.submitList(mapUi(Carrito.items))
                Toast.makeText(this, "Detalles actualizados", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar") { _, _ ->
                adapter.notifyItemChanged(position)
            }
            .show()
    }


    private fun drawIcon(c: Canvas, emoji: String, rect: RectF, paint: Paint) {
        paint.color = Color.WHITE
        paint.textSize = 48f
        paint.textAlign = Paint.Align.CENTER
        val x = rect.centerX()
        val y = rect.centerY() - ((paint.descent() + paint.ascent()) / 2)
        c.drawText(emoji, x, y, paint)
    }

    private fun limpiarSwipe() {
        recyclerView.post {
            adapter.notifyItemChanged(swipedPosition)
            swipedPosition = -1
            swipeOffset = 0f
        }
    }

    private fun mostrarDialogoEditar(item: ItemCarrito, position: Int) {
        val input = EditText(this).apply {
            hint = "Nueva cantidad"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(item.cantidad.toString())
        }

        AlertDialog.Builder(this)
            .setTitle("Editar cantidad")
            .setView(input)
            .setPositiveButton("Actualizar") { _, _ ->
                val nuevaCantidad = input.text.toString().toIntOrNull()
                if (nuevaCantidad != null && nuevaCantidad > 0) {
                    val nuevoItem = item.copy(cantidad = nuevaCantidad)
                    Carrito.items[position] = nuevoItem
                    adapter.submitList(mapUi(Carrito.items))
                    actualizarTotal()
                    Toast.makeText(this, "Cantidad actualizada", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Cantidad inválida", Toast.LENGTH_SHORT).show()
                    adapter.notifyItemChanged(position)
                }
            }
            .setNegativeButton("Cancelar") { _, _ ->
                adapter.notifyItemChanged(position)
            }
            .show()

    }

    private fun eliminarItem(position: Int) {
        Carrito.items.removeAt(position)
        adapter.submitList(mapUi(Carrito.items))
        actualizarTotal()
        Snackbar.make(recyclerView, "Producto eliminado", Snackbar.LENGTH_SHORT).show()
    }

    private fun confirmarLimpiarCarrito() {
        AlertDialog.Builder(this)
            .setTitle("¿Vaciar carrito?")
            .setMessage("Esto eliminará todos los productos agregados.")
            .setPositiveButton("Sí, vaciar") { _, _ ->
                Carrito.items.clear()
                adapter.submitList(emptyList())
                actualizarTotal()
                Toast.makeText(this, "Carrito vaciado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun actualizarTotal() {
        val totalBruto = Carrito.items.sumOf { (it.producto.precio * it.cantidad) - it.descuento }
        val totalRedondeado = redondearAGuatemalteco(totalBruto)
        textTotal.text = "Total: Q${"%.2f".format(totalRedondeado)}"
    }

    // Map existing domain ItemCarrito to UI-only CarritoUiItem for the adapter
    private fun mapUi(items: List<ItemCarrito>): List<CarritoUiItem> {
        val df = java.text.DecimalFormat("#,##0.00")
        return items.map { item ->
            val subtotalSinDescuento = item.producto.precio * item.cantidad
            val subtotalConDescuento = subtotalSinDescuento - item.descuento
            val tieneDesc = item.descuento > 0.0

            CarritoUiItem(
                id = item.producto.id.toString(),
                nombreProducto = item.producto.nombre,
                cantidadTexto = "Cantidad: ${item.cantidad}",
                subtotalTexto = "Subtotal: Q${df.format(subtotalConDescuento)}",
                descuentoTexto = if (tieneDesc) "Descuento: -Q${df.format(item.descuento)}" else "",
                tieneDescuento = tieneDesc,
                detallesTexto = if (item.detalles.isNotBlank()) item.detalles else "",
                stockTexto = "Stock actual: ${item.producto.stock}",
                stockColor = if (item.producto.stock < 0) android.graphics.Color.RED else android.graphics.Color.BLACK
            )
        }
    }


    private fun mostrarDialogoProductoManual() {
        val inputNombre = EditText(this).apply {
            hint = "Nombre del ítem"
        }

        val inputCantidad = EditText(this).apply {
            hint = "Cantidad"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val inputPrecioUnitario = EditText(this).apply {
            hint = "Precio unitario Q"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val inputDetalles = EditText(this).apply {
            hint = "Detalles (opcional)"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
            addView(inputNombre)
            addView(inputCantidad)
            addView(inputPrecioUnitario)
            addView(inputDetalles)
        }

        AlertDialog.Builder(this)
            .setTitle("Agregar ítem personalizado")
            .setView(layout)
            .setPositiveButton("Agregar") { _, _ ->
                val nombre = inputNombre.text.toString().ifBlank { "Personalizado" }
                val cantidad = inputCantidad.text.toString().toIntOrNull() ?: 1
                val precioUnitario = inputPrecioUnitario.text.toString().toDoubleOrNull() ?: 0.0
                val detalles = inputDetalles.text.toString().trim()

                // Asignar un ID negativo único para ítems manuales
                val idsNegativos = Carrito.items.map { it.producto.id }.filter { it < 0 }
                val nuevoIdManual = if (idsNegativos.isEmpty()) -1 else (idsNegativos.minOrNull()!! - 1)

                val productoFicticio = Producto(
                    id = nuevoIdManual,
                    nombre = nombre,
                    precio = precioUnitario,
                    stock = 0,
                    descripcion = "Ajuste manual",
                    imagenUrl = "",
                    categoria = ""
                )

                val nuevoItem = ItemCarrito(
                    producto = productoFicticio,
                    cantidad = cantidad,
                    detalles = detalles.ifBlank { "" },
                    descuento = 0.0
                )

                runOnUiThread {
                    Carrito.items.add(nuevoItem)
                    adapter.submitList(mapUi(Carrito.items))
                    actualizarTotal()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }


    private fun redondearAGuatemalteco(total: Double): Double {
        // Trabajamos en centavos para evitar errores de double
        val totalCent = kotlin.math.round(total * 100).toInt()
        val quetzales = totalCent / 100
        val centavos = totalCent % 100

        val objetivos = intArrayOf(0, 25, 50, 75, 100)
        // Si hay empate (por ejemplo 62.5 entre 50 y 75), preferimos redondear hacia arriba
        val masCercano = objetivos.minBy { k ->
            val dist = kotlin.math.abs(k - centavos)
            // truco: en empate favorece el mayor
            dist * 2 - if (k >= centavos) 1 else 0
        }

        return if (masCercano == 100) {
            (quetzales + 1).toDouble()       // pasa al siguiente quetzal
        } else {
            quetzales + (masCercano / 100.0) // 0.00, 0.25, 0.50 o 0.75
        }
    }



}