package com.are.distribuidora.administrador.factura

// Clean RecyclerView ListAdapter dedicated only to binding and click handling.
// No Firestore calls, no calculations, no business logic.

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.are.distribuidora.R

class CarritoAdapter(
    private val onEditar: (CarritoUiItem, Int) -> Unit,
    private val onEliminar: (Int) -> Unit
) : ListAdapter<CarritoUiItem, CarritoAdapter.CarritoViewHolder>(DIFF) {

    class CarritoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nombre: TextView = view.findViewById(R.id.textNombreCarrito)
        val cantidad: TextView = view.findViewById(R.id.textCantidadCarrito)
        val subtotal: TextView = view.findViewById(R.id.textSubtotalCarrito)
        val detalles: TextView = view.findViewById(R.id.textDetallesCarrito)
        val stock: TextView = view.findViewById(R.id.textStockCarrito)
        val descuento: TextView = view.findViewById(R.id.textDescuentoCarrito)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CarritoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_carrito, parent, false)
        return CarritoViewHolder(view)
    }

    override fun onBindViewHolder(holder: CarritoViewHolder, position: Int) {
        val item = getItem(position)

        holder.nombre.text = item.nombreProducto
        holder.cantidad.text = item.cantidadTexto
        holder.subtotal.text = item.subtotalTexto

        holder.descuento.visibility = if (item.tieneDescuento) View.VISIBLE else View.GONE
        holder.descuento.text = item.descuentoTexto

        holder.detalles.text = item.detallesTexto
        holder.stock.text = item.stockTexto
        holder.stock.setTextColor(item.stockColor)

        holder.itemView.setOnClickListener { onEditar(item, position) }
        holder.itemView.setOnLongClickListener {
            onEliminar(position)
            true
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<CarritoUiItem>() {
            override fun areItemsTheSame(oldItem: CarritoUiItem, newItem: CarritoUiItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: CarritoUiItem, newItem: CarritoUiItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
