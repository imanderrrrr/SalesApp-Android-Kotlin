package com.are.distribuidora.data.repositories

import com.are.distribuidora.administrador.factura.ItemCarrito
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

// Firestore-backed implementation of CarritoRepository.
// Encapsulates all Firestore access for carrito items.
class FirestoreCarritoRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : CarritoRepository {

    override suspend fun getCarritoOnce(uid: String): List<ItemCarrito> {
        val snapshot = firestore.collection("carritos")
            .document(uid)
            .collection("items")
            .get()
            .await()
        return snapshot.toObjects(ItemCarrito::class.java)
    }

    override fun observeCarrito(uid: String): Flow<List<ItemCarrito>> = callbackFlow {
        val col = firestore.collection("carritos").document(uid).collection("items")
        val registration = col.addSnapshotListener { snapshot, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            val items = snapshot?.toObjects(ItemCarrito::class.java) ?: emptyList()
            trySend(items).isSuccess
        }
        awaitClose { registration.remove() }
    }

    override suspend fun addItemToCarrito(uid: String, item: ItemCarrito) {
        firestore.collection("carritos").document(uid).collection("items")
            .add(item)
            .await()
    }

    override suspend fun updateItemInCarrito(uid: String, item: ItemCarrito) {
        firestore.collection("carritos").document(uid).collection("items")
            .document(item.producto.id.toString())
            .set(item, SetOptions.merge())
            .await()
    }

    override suspend fun removeItemFromCarrito(uid: String, itemId: String) {
        firestore.collection("carritos").document(uid).collection("items")
            .document(itemId)
            .delete()
            .await()
    }
}
