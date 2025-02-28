// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.pullrequest.ui.toolwindow

import org.jetbrains.plugins.github.api.data.GithubIssueState
import org.jetbrains.plugins.github.pullrequest.data.GHPRSearchQuery
import org.jetbrains.plugins.github.pullrequest.data.GHPRSearchQuery.QualifierName
import org.jetbrains.plugins.github.pullrequest.data.GHPRSearchQuery.Term
import org.jetbrains.plugins.github.pullrequest.data.GHPRSearchQuery.Term.Qualifier
import org.jetbrains.plugins.github.pullrequest.data.GHPRSearchQuery.Term.QueryPart

internal data class GHPRListSearchState(val searchQuery: String? = null,
                                        val state: State? = null,
                                        val assignee: String? = null,
                                        val reviewState: ReviewState? = null,
                                        val author: String? = null,
                                        val label: String? = null) {
  val isEmpty = searchQuery == null && state == null && assignee == null && reviewState == null && author == null && label == null

  fun toQuery(): GHPRSearchQuery? {
    val terms = mutableListOf<Term<*>>()

    if (searchQuery != null) {
      terms.add(QueryPart(searchQuery))
    }

    if (state != null) {
      val term = when (state) {
        State.OPEN -> Qualifier.Enum(QualifierName.`is`, GithubIssueState.open)
        State.CLOSED -> Qualifier.Enum(QualifierName.`is`, GithubIssueState.closed)
        State.MERGED -> Qualifier.Simple(QualifierName.`is`, "merged")
      }
      terms.add(term)
    }

    if (assignee != null) {
      terms.add(Qualifier.Simple(QualifierName.assignee, assignee))
    }

    if (reviewState != null) {
      val term = when (reviewState) {
        ReviewState.NO_REVIEW -> Qualifier.Simple(QualifierName.review, "none")
        ReviewState.REQUIRED -> Qualifier.Simple(QualifierName.review, "required")
        ReviewState.APPROVED -> Qualifier.Simple(QualifierName.review, "approved")
        ReviewState.CHANGES_REQUESTED -> Qualifier.Simple(QualifierName.review, "changes-requested")
        ReviewState.REVIEWED_BY_ME -> Qualifier.Simple(QualifierName.reviewedBy, "@me")
        ReviewState.NOT_REVIEWED_BY_ME -> Qualifier.Simple(QualifierName.reviewedBy, "@me").not()
        ReviewState.AWAITING_REVIEW -> Qualifier.Simple(QualifierName.reviewRequested, "@me")
      }
      terms.add(term)
    }

    if (author != null) {
      terms.add(Qualifier.Simple(QualifierName.author, author))
    }

    if (label != null) {
      terms.add(Qualifier.Simple(QualifierName.label, label))
    }

    if (terms.isEmpty()) return null
    return GHPRSearchQuery(terms)
  }

  companion object {
    val DEFAULT = GHPRListSearchState(state = State.OPEN)
    val EMPTY = GHPRListSearchState()
  }

  enum class State {
    OPEN,
    CLOSED,
    MERGED
  }

  enum class ReviewState {
    NO_REVIEW,
    REQUIRED,
    APPROVED,
    CHANGES_REQUESTED,
    REVIEWED_BY_ME,
    NOT_REVIEWED_BY_ME,
    AWAITING_REVIEW
  }
}
