package com.eds.overlay.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eds.overlay.R
import com.eds.overlay.databinding.ItemQuickIssueBinding
import com.eds.overlay.databinding.SheetQuickIssuesBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Modal BottomSheet for selecting one or more quick feedback issue categories.
 *
 * Replaces dense chip clouds with an accessible, scrollable checklist where
 * each issue label has room to display its full text without artificial truncation.
 */
class QuickIssuesSheet : BottomSheetDialogFragment() {

    private var _binding: SheetQuickIssuesBinding? = null
    private val binding get() = _binding!!

    private var issueList: List<String> = emptyList()
    private val selectedIndices: MutableSet<Int> = mutableSetOf()
    private var onConfirmedListener: ((Set<Int>) -> Unit)? = null

    companion object {
        const val TAG = "QuickIssuesSheet"

        /**
         * Creates a new instance of [QuickIssuesSheet].
         *
         * @param issues Complete list of localized issue descriptions.
         * @param initialSelected Zero-based indices of previously selected issues.
         * @param onConfirmed Callback invoked when the user confirms their selection.
         */
        fun newInstance(
            issues: List<String>,
            initialSelected: Set<Int>,
            onConfirmed: (Set<Int>) -> Unit
        ): QuickIssuesSheet {
            return QuickIssuesSheet().apply {
                this.issueList = issues
                this.selectedIndices.addAll(initialSelected)
                this.onConfirmedListener = onConfirmed
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetQuickIssuesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = QuickIssuesAdapter(issueList, selectedIndices) { index, isChecked ->
            if (isChecked) {
                selectedIndices.add(index)
            } else {
                selectedIndices.remove(index)
            }
        }

        binding.rvQuickIssues.layoutManager = LinearLayoutManager(requireContext())
        binding.rvQuickIssues.adapter = adapter

        binding.btnConfirmIssues.setOnClickListener {
            onConfirmedListener?.invoke(selectedIndices.toSet())
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * RecyclerView adapter rendering each selectable issue as an interactive row.
     */
    private class QuickIssuesAdapter(
        private val items: List<String>,
        private val selectedSet: MutableSet<Int>,
        private val onToggle: (Int, Boolean) -> Unit
    ) : RecyclerView.Adapter<QuickIssuesAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemQuickIssueBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemQuickIssueBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val text = items[position]
            val isChecked = selectedSet.contains(position)

            holder.binding.tvIssueText.text = text
            holder.binding.cbIssue.isChecked = isChecked

            holder.binding.root.setOnClickListener {
                val newChecked = !holder.binding.cbIssue.isChecked
                holder.binding.cbIssue.isChecked = newChecked
                onToggle(position, newChecked)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
