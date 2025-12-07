package com.are.distribuidora.data.repositories

import com.are.distribuidora.basedatos.Producto
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

// Firestore implementation for ProductosRepository, including stock updates via claveGlobal.
class FirestoreProductosRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : ProductosRepository {

    override suspend fun getProductosOnce(): List<Producto> {
        val snapshot = firestore.collection("productos").get().await()
        return snapshot.toObjects(Producto::class.java)
    }

    override fun observeProductos(): Flow<List<Producto>> = callbackFlow {
        val registration = firestore.collection("productos")
            .addSnapshotListener { snapshot, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                val productos = snapshot?.toObjects(Producto::class.java) ?: emptyList()
                trySend(productos).isSuccess
            }
        awaitClose { registration.remove() }
    }

    override suspend fun updateProductoStockByClaveGlobal(claveGlobal: String, nuevoStock: Int) {
        val query = firestore.collection("productos")
            .whereEqualTo("claveGlobal", claveGlobal)
            .get()
            .await()
        val doc = query.documents.firstOrNull()
        if (doc != null) {
            doc.reference.update(
                mapOf(
                    "stock" to nuevoStock,
                    "pendienteSync" to false
                )
            ).await()
        } else {
            throw IllegalStateException("No se encontró producto con claveGlobal=$claveGlobal")
        }
    }
}
