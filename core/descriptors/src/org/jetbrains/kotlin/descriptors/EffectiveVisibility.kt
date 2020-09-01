/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.descriptors

import org.jetbrains.kotlin.descriptors.EffectiveVisibility.Permissiveness
import org.jetbrains.kotlin.types.AbstractTypeChecker
import org.jetbrains.kotlin.types.AbstractTypeCheckerContext
import org.jetbrains.kotlin.types.model.KotlinTypeMarker
import org.jetbrains.kotlin.types.model.typeConstructor

sealed class EffectiveVisibility(val name: String, val publicApi: Boolean = false, val privateApi: Boolean = false) {
    override fun toString() = name

    //                    Public
    //                /--/   |  \-------------\
    // Protected(Base)       |                 \
    //       |         Protected(Other)        Internal = PackagePrivate
    // Protected(Derived) |                   /     \
    //             |      |                  /    InternalProtected(Base)
    //       ProtectedBound                 /        \
    //                    \                /       /InternalProtected(Derived)
    //                     \InternalProtectedBound/
    //                              |
    //                           Private = Local


    object Private : EffectiveVisibility("private", privateApi = true) {
        override fun relation(other: EffectiveVisibility): Permissiveness =
            if (this == other || Local == other) Permissiveness.SAME else Permissiveness.LESS

        override fun toVisibility(): Visibility = Visibilities.PRIVATE
    }

    // Effectively same as Private
    object Local : EffectiveVisibility("local") {
        override fun relation(other: EffectiveVisibility): Permissiveness =
            if (this == other || Private == other) Permissiveness.SAME else Permissiveness.LESS

        override fun toVisibility(): Visibility = Visibilities.LOCAL
    }

    object Public : EffectiveVisibility("public", publicApi = true) {
        override fun relation(other: EffectiveVisibility): Permissiveness =
            if (this == other) Permissiveness.SAME else Permissiveness.MORE

        override fun toVisibility(): Visibility = Visibilities.PUBLIC
    }

    abstract class InternalOrPackage protected constructor(internal: Boolean) : EffectiveVisibility(
        if (internal) "internal" else "public/*package*/"
    ) {
        override fun relation(other: EffectiveVisibility): Permissiveness = when (other) {
            Public -> Permissiveness.LESS
            Private, Local, InternalProtectedBound, is InternalProtected -> Permissiveness.MORE
            is InternalOrPackage -> Permissiveness.SAME
            ProtectedBound, is Protected -> Permissiveness.UNKNOWN
        }

        override fun lowerBound(other: EffectiveVisibility) = when (other) {
            Public -> this
            Private, Local, InternalProtectedBound, is InternalOrPackage, is InternalProtected -> other
            is Protected -> InternalProtected(other.containerType, other.typeContext)
            ProtectedBound -> InternalProtectedBound
        }
    }

    object Internal : InternalOrPackage(true) {
        override fun toVisibility(): Visibility = Visibilities.INTERNAL
    }

    object PackagePrivate : InternalOrPackage(false) {
        override fun toVisibility(): Visibility = Visibilities.PRIVATE
    }

    class Protected(
        val containerType: KotlinTypeMarker?,
        val typeContext: AbstractTypeCheckerContext
    ) : EffectiveVisibility("protected", publicApi = true) {

        override fun equals(other: Any?) = (other is Protected && containerType == other.containerType)

        override fun hashCode() = containerType?.hashCode() ?: 0

        override fun toString() = "${super.toString()} (in ${containerType?.typeConstructor(typeContext) ?: '?'})"

        override fun relation(other: EffectiveVisibility): Permissiveness = when (other) {
            Public -> Permissiveness.LESS
            Private, Local, ProtectedBound, InternalProtectedBound -> Permissiveness.MORE
            is Protected -> containerRelation(containerType, other.containerType, typeContext)
            is InternalProtected -> when (containerRelation(containerType, other.containerType, typeContext)) {
                // Protected never can be less permissive than internal & protected
                Permissiveness.SAME, Permissiveness.MORE -> Permissiveness.MORE
                Permissiveness.UNKNOWN, Permissiveness.LESS -> Permissiveness.UNKNOWN
            }
            is InternalOrPackage -> Permissiveness.UNKNOWN
        }

        override fun lowerBound(other: EffectiveVisibility) = when (other) {
            Public -> this
            Private, Local, ProtectedBound, InternalProtectedBound -> other
            is Protected -> when (relation(other)) {
                Permissiveness.SAME, Permissiveness.MORE -> this
                Permissiveness.LESS -> other
                Permissiveness.UNKNOWN -> ProtectedBound
            }
            is InternalProtected -> when (relation(other)) {
                Permissiveness.LESS -> other
                else -> InternalProtectedBound
            }
            is InternalOrPackage -> InternalProtected(containerType, typeContext)
        }

        override fun toVisibility(): Visibility = Visibilities.PROTECTED
    }

    // Lower bound for all protected visibilities
    object ProtectedBound : EffectiveVisibility("protected (in different classes)", publicApi = true) {
        override fun relation(other: EffectiveVisibility): Permissiveness = when (other) {
            Public, is Protected -> Permissiveness.LESS
            Private, Local, InternalProtectedBound -> Permissiveness.MORE
            ProtectedBound -> Permissiveness.SAME
            is InternalOrPackage, is InternalProtected -> Permissiveness.UNKNOWN
        }

        override fun lowerBound(other: EffectiveVisibility) = when (other) {
            Public, is Protected -> this
            Private, Local, ProtectedBound, InternalProtectedBound -> other
            is InternalOrPackage, is InternalProtected -> InternalProtectedBound
        }

        override fun toVisibility(): Visibility = Visibilities.PROTECTED
    }

    // Lower bound for internal and protected(C)
    class InternalProtected(
        val containerType: KotlinTypeMarker?,
        val typeContext: AbstractTypeCheckerContext,
    ) : EffectiveVisibility("internal & protected") {

        override fun equals(other: Any?) = (other is InternalProtected && containerType == other.containerType)

        override fun hashCode() = containerType?.hashCode() ?: 0

        override fun toString() = "${super.toString()} (in ${containerType?.typeConstructor(typeContext) ?: '?'})"

        override fun relation(other: EffectiveVisibility): Permissiveness = when (other) {
            Public, is InternalOrPackage -> Permissiveness.LESS
            Private, Local, InternalProtectedBound -> Permissiveness.MORE
            is InternalProtected -> containerRelation(containerType, other.containerType, typeContext)
            is Protected -> when (containerRelation(containerType, other.containerType, typeContext)) {
                // Internal & protected never can be more permissive than just protected
                Permissiveness.SAME, Permissiveness.LESS -> Permissiveness.LESS
                Permissiveness.UNKNOWN, Permissiveness.MORE -> Permissiveness.UNKNOWN
            }
            ProtectedBound -> Permissiveness.UNKNOWN
        }

        override fun lowerBound(other: EffectiveVisibility) = when (other) {
            Public, is InternalOrPackage -> this
            Private, Local, InternalProtectedBound -> other
            is Protected, is InternalProtected -> when (relation(other)) {
                Permissiveness.SAME, Permissiveness.MORE -> this
                Permissiveness.LESS -> other
                Permissiveness.UNKNOWN -> InternalProtectedBound
            }
            ProtectedBound -> InternalProtectedBound
        }

        override fun toVisibility(): Visibility = Visibilities.PRIVATE
    }

    // Lower bound for internal and protected lower bound
    object InternalProtectedBound : EffectiveVisibility("internal & protected (in different classes)") {
        override fun relation(other: EffectiveVisibility): Permissiveness = when (other) {
            Public, is Protected, is InternalProtected, ProtectedBound, is InternalOrPackage -> Permissiveness.LESS
            Private, Local -> Permissiveness.MORE
            InternalProtectedBound -> Permissiveness.SAME
        }

        override fun toVisibility(): Visibility = Visibilities.PRIVATE
    }

    enum class Permissiveness {
        LESS,
        SAME,
        MORE,
        UNKNOWN
    }

    abstract fun relation(other: EffectiveVisibility): Permissiveness

    abstract fun toVisibility(): Visibility

    internal open fun lowerBound(other: EffectiveVisibility) = when (relation(other)) {
        Permissiveness.SAME, Permissiveness.LESS -> this
        Permissiveness.MORE -> other
        Permissiveness.UNKNOWN -> Private
    }
}

enum class RelationToType(val description: String) {
    CONSTRUCTOR(""),
    CONTAINER(" containing declaration"),
    ARGUMENT(" argument"),
    ARGUMENT_CONTAINER(" argument containing declaration");

    fun containerRelation() = when (this) {
        CONSTRUCTOR, CONTAINER -> CONTAINER
        ARGUMENT, ARGUMENT_CONTAINER -> ARGUMENT_CONTAINER
    }

    override fun toString() = description
}

internal fun containerRelation(
    first: KotlinTypeMarker?,
    second: KotlinTypeMarker?,
    typeContext: AbstractTypeCheckerContext
): Permissiveness {
    return when {
        first == null || second == null -> Permissiveness.UNKNOWN
        first == second -> Permissiveness.SAME
        AbstractTypeChecker.isSubtypeOf(typeContext, first, second) -> Permissiveness.LESS
        AbstractTypeChecker.isSubtypeOf(typeContext, second, first) -> Permissiveness.MORE
        else -> Permissiveness.UNKNOWN
    }
}

