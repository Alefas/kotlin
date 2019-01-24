/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.types.checker

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.resolve.calls.inference.CapturedType
import org.jetbrains.kotlin.resolve.calls.inference.CapturedTypeConstructor
import org.jetbrains.kotlin.resolve.constants.IntegerValueTypeConstructor
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.AbstractTypeChecker.doIsSubTypeOf
import org.jetbrains.kotlin.types.AbstractTypeCheckerContext.SeveralSupertypesWithSameConstructorPolicy.*
import org.jetbrains.kotlin.types.AbstractTypeCheckerContext.SupertypesPolicy
import org.jetbrains.kotlin.types.model.KotlinTypeIM
import org.jetbrains.kotlin.types.model.SimpleTypeIM
import org.jetbrains.kotlin.types.typeUtil.asTypeProjection
import org.jetbrains.kotlin.types.typeUtil.isAnyOrNullableAny
import org.jetbrains.kotlin.types.typeUtil.makeNullable
import org.jetbrains.kotlin.utils.SmartList
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

object StrictEqualityTypeChecker {

    private val context = object : ClassicTypeSystemContext {}
    /**
     * String! != String & A<String!> != A<String>, also A<in Nothing> != A<out Any?>
     * also A<*> != A<out Any?>
     * different error types non-equals even errorTypeEqualToAnything
     */
    fun strictEqualTypes(a: UnwrappedType, b: UnwrappedType): Boolean {
        return AbstractStrictEqualityTypeChecker.strictEqualTypes(context, a, b)
    }

    fun strictEqualTypes(a: SimpleType, b: SimpleType): Boolean {
        return AbstractStrictEqualityTypeChecker.strictEqualTypes(context, a, b)
    }

}

object ErrorTypesAreEqualToAnything : KotlinTypeChecker {
    override fun isSubtypeOf(subtype: KotlinType, supertype: KotlinType): Boolean =
        NewKotlinTypeChecker.run { TypeCheckerContext(true).isSubtypeOf(subtype.unwrap(), supertype.unwrap()) }

    override fun equalTypes(a: KotlinType, b: KotlinType): Boolean =
        NewKotlinTypeChecker.run { TypeCheckerContext(true).equalTypes(a.unwrap(), b.unwrap()) }
}

object NewKotlinTypeChecker : KotlinTypeChecker {
    override fun isSubtypeOf(subtype: KotlinType, supertype: KotlinType): Boolean =
        TypeCheckerContext(true).isSubtypeOf(subtype.unwrap(), supertype.unwrap()) // todo fix flag errorTypeEqualsToAnything

    override fun equalTypes(a: KotlinType, b: KotlinType): Boolean =
        TypeCheckerContext(false).equalTypes(a.unwrap(), b.unwrap())

    fun TypeCheckerContext.equalTypes(a: UnwrappedType, b: UnwrappedType): Boolean {
        return AbstractTypeChecker.equalTypes(this, a, b)
    }

    fun TypeCheckerContext.isSubtypeOf(subType: UnwrappedType, superType: UnwrappedType): Boolean {
        return AbstractTypeChecker.isSubtypeOf(this, subType, superType)
    }

    fun TypeCheckerContext.transformAndIsSubTypeOf(subType: UnwrappedType, superType: UnwrappedType): Boolean {
        if (subType === superType) return true
        val newSubType = transformToNewType(subType)
        val newSuperType = transformToNewType(superType)
        return doIsSubTypeOf(newSubType, newSuperType)
    }


    fun transformToNewType(type: SimpleType): SimpleType {
        val constructor = type.constructor
        when (constructor) {
            // Type itself can be just SimpleTypeImpl, not CapturedType. see KT-16147
            is CapturedTypeConstructor -> {
                val lowerType = constructor.typeProjection.takeIf { it.projectionKind == Variance.IN_VARIANCE }?.type?.unwrap()

                // it is incorrect calculate this type directly because of recursive star projections
                if (constructor.newTypeConstructor == null) {
                    constructor.newTypeConstructor =
                        NewCapturedTypeConstructor(constructor.typeProjection, constructor.supertypes.map { it.unwrap() })
                }
                return NewCapturedType(
                    CaptureStatus.FOR_SUBTYPING, constructor.newTypeConstructor!!,
                    lowerType, type.annotations, type.isMarkedNullable
                )
            }

            is IntegerValueTypeConstructor -> {
                val newConstructor =
                    IntersectionTypeConstructor(constructor.supertypes.map { TypeUtils.makeNullableAsSpecified(it, type.isMarkedNullable) })
                return KotlinTypeFactory.simpleTypeWithNonTrivialMemberScope(
                    type.annotations,
                    newConstructor,
                    listOf(),
                    false,
                    type.memberScope
                )
            }

            is IntersectionTypeConstructor -> if (type.isMarkedNullable) {
                val newConstructor = constructor.transformComponents(transform = { it.makeNullable() }) ?: constructor
                return KotlinTypeFactory.simpleTypeWithNonTrivialMemberScope(
                    type.annotations,
                    newConstructor,
                    listOf(),
                    false,
                    newConstructor.createScopeForKotlinType()
                )
            }
        }

        return type
    }

    fun transformToNewType(type: UnwrappedType): UnwrappedType =
        when (type) {
            is SimpleType -> transformToNewType(type)
            is FlexibleType -> {
                val newLower = transformToNewType(type.lowerBound)
                val newUpper = transformToNewType(type.upperBound)
                if (newLower !== type.lowerBound || newUpper !== type.upperBound) {
                    KotlinTypeFactory.flexibleType(newLower, newUpper)
                } else {
                    type
                }
            }
        }.inheritEnhancement(type)

    private fun TypeCheckerContext.hasNothingSupertype(type: SimpleType) = // todo add tests
        anySupertype(type, { KotlinBuiltIns.isNothingOrNullableNothing(it as SimpleType) }) {
            require(it is SimpleType)
            if (it.isClassType) {
                SupertypesPolicy.None
            } else {
                SupertypesPolicy.LowerIfFlexible
            }
        }

    fun TypeCheckerContext.isSubtypeOfForSingleClassifierType(subType: SimpleType, superType: SimpleType): Boolean {
        assert(subType.isSingleClassifierType || subType.isIntersectionType || subType.isAllowedTypeVariable) {
            "Not singleClassifierType and not intersection subType: $subType"
        }
        assert(superType.isSingleClassifierType || superType.isAllowedTypeVariable) {
            "Not singleClassifierType superType: $superType"
        }

        if (!NullabilityChecker.isPossibleSubtype(this, subType, superType)) return false

        val superConstructor = superType.constructor

        if (subType.constructor == superConstructor && superConstructor.parameters.isEmpty()) return true
        if (superType.isAnyOrNullableAny()) return true

        val supertypesWithSameConstructor = findCorrespondingSupertypes(subType, superConstructor)
        when (supertypesWithSameConstructor.size) {
            0 -> return hasNothingSupertype(subType) // todo Nothing & Array<Number> <: Array<String>
            1 -> return isSubtypeForSameConstructor(supertypesWithSameConstructor.first().arguments, superType)

            else -> { // at least 2 supertypes with same constructors. Such case is rare
                when (sameConstructorPolicy) {
                    FORCE_NOT_SUBTYPE -> return false
                    TAKE_FIRST_FOR_SUBTYPING -> return isSubtypeForSameConstructor(
                        supertypesWithSameConstructor.first().arguments,
                        superType
                    )

                    CHECK_ANY_OF_THEM,
                    INTERSECT_ARGUMENTS_AND_CHECK_AGAIN ->
                        if (supertypesWithSameConstructor.any { isSubtypeForSameConstructor(it.arguments, superType) }) return true
                }

                if (sameConstructorPolicy != INTERSECT_ARGUMENTS_AND_CHECK_AGAIN) return false

                val newArguments = superConstructor.parameters.mapIndexed { index, _ ->
                    val allProjections = supertypesWithSameConstructor.map {
                        it.arguments.getOrNull(index)?.takeIf { it.projectionKind == Variance.INVARIANT }?.type?.unwrap()
                            ?: error("Incorrect type: $it, subType: $subType, superType: $superType")
                    }

                    // todo discuss
                    intersectTypes(allProjections).asTypeProjection()
                }

                return isSubtypeForSameConstructor(newArguments, superType)
            }
        }
    }

    private fun TypeCheckerContext.collectAndFilter(classType: SimpleType, constructor: TypeConstructor) =
        selectOnlyPureKotlinSupertypes(collectAllSupertypesWithGivenTypeConstructor(classType, constructor))

    // nullability was checked earlier via nullabilityChecker
    // should be used only if you really sure that it is correct
    fun TypeCheckerContext.findCorrespondingSupertypes(
        baseType: SimpleType,
        constructor: TypeConstructor
    ): List<SimpleType> {
        if (baseType.isClassType) {
            return collectAndFilter(baseType, constructor)
        }

        // i.e. superType is not a classType
        if (constructor.declarationDescriptor !is ClassDescriptor) {
            return collectAllSupertypesWithGivenTypeConstructor(baseType, constructor)
        }

        // todo add tests
        val classTypeSupertypes = SmartList<SimpleType>()
        anySupertype(baseType, { false }) {
            require(it is SimpleType)
            if (it.isClassType) {
                classTypeSupertypes.add(it)
                SupertypesPolicy.None
            } else {
                SupertypesPolicy.LowerIfFlexible
            }
        }

        return classTypeSupertypes.flatMap { collectAndFilter(it, constructor) }
    }

    private fun TypeCheckerContext.collectAllSupertypesWithGivenTypeConstructor(
        baseType: SimpleType,
        constructor: TypeConstructor
    ): List<SimpleType> {
        if (constructor.declarationDescriptor.safeAs<ClassDescriptor>()?.isCommonFinalClass == true) {
            return if (areEqualTypeConstructors(baseType.constructor, constructor))
                listOf(captureFromArguments(baseType, CaptureStatus.FOR_SUBTYPING) ?: baseType)
            else
                emptyList()
        }

        val result: MutableList<SimpleType> = SmartList()

        anySupertype(baseType, { false }) {
            require(it is SimpleType)
            val current = captureFromArguments(it, CaptureStatus.FOR_SUBTYPING) ?: it

            when {
                areEqualTypeConstructors(current.constructor, constructor) -> {
                    result.add(current)
                    SupertypesPolicy.None
                }
                current.arguments.isEmpty() -> {
                    SupertypesPolicy.LowerIfFlexible
                }
                else -> {
                    val substitutor = TypeConstructorSubstitution.create(current).buildSubstitutor()

                    object : SupertypesPolicy.DoCustomTransform() {
                        override fun transformType(context: AbstractTypeCheckerContext, type: KotlinTypeIM): SimpleTypeIM {
                            return substitutor.safeSubstitute(
                                type.lowerBoundIfFlexible() as KotlinType,
                                Variance.INVARIANT
                            ).asSimpleType()!!
                        }
                    }
                }
            }
        }

        return result
    }

    private val ClassDescriptor.isCommonFinalClass: Boolean
        get() = isFinalClass && kind != ClassKind.ENUM_ENTRY && kind != ClassKind.ANNOTATION_CLASS

    /**
     * If we have several paths to some interface, we should prefer pure kotlin path.
     * Example:
     *
     * class MyList : AbstractList<String>(), MutableList<String>
     *
     * We should see `String` in `get` function and others, also MyList is not subtype of MutableList<String?>
     *
     * More tests: javaAndKotlinSuperType & purelyImplementedCollection folder
     */
    private fun selectOnlyPureKotlinSupertypes(supertypes: List<SimpleType>): List<SimpleType> {
        if (supertypes.size < 2) return supertypes

        val allPureSupertypes = supertypes.filter { it.arguments.all { !it.type.isFlexible() } }
        return if (allPureSupertypes.isNotEmpty()) allPureSupertypes else supertypes
    }

    fun effectiveVariance(declared: Variance, useSite: Variance): Variance? {
        if (declared == Variance.INVARIANT) return useSite
        if (useSite == Variance.INVARIANT) return declared

        // both not INVARIANT
        if (declared == useSite) return declared

        // composite In with Out
        return null
    }

    private fun TypeCheckerContext.isSubtypeForSameConstructor(
        capturedSubArguments: List<TypeProjection>,
        superType: SimpleType
    ): Boolean {
        return AbstractTypeChecker.run {
            isSubtypeForSameConstructor(capturedSubArguments, superType)
        }
    }

}

object NullabilityChecker {

    // this method checks only nullability
    fun isPossibleSubtype(context: TypeCheckerContext, subType: SimpleType, superType: SimpleType): Boolean =
        context.runIsPossibleSubtype(subType, superType)

    fun isSubtypeOfAny(type: UnwrappedType): Boolean =
        TypeCheckerContext(false).hasNotNullSupertype(type.lowerIfFlexible(), SupertypesPolicy.LowerIfFlexible)

    fun hasPathByNotMarkedNullableNodes(start: SimpleType, end: TypeConstructor) =
        TypeCheckerContext(false).hasPathByNotMarkedNullableNodes(start, end)

    private fun TypeCheckerContext.runIsPossibleSubtype(subType: SimpleType, superType: SimpleType): Boolean {
        // it makes for case String? & Any <: String
        assert(subType.isIntersectionType || subType.isSingleClassifierType || subType.isAllowedTypeVariable) {
            "Not singleClassifierType superType: $superType"
        }
        assert(superType.isSingleClassifierType || superType.isAllowedTypeVariable) {
            "Not singleClassifierType superType: $superType"
        }

        // superType is actually nullable
        if (superType.isMarkedNullable) return true

        // i.e. subType is definitely not null
        if (subType.isDefinitelyNotNullType) return true

        // i.e. subType is not-nullable
        if (hasNotNullSupertype(subType, SupertypesPolicy.LowerIfFlexible)) return true

        // i.e. subType hasn't not-null supertype and isn't definitely not-null, but superType is definitely not-null
        if (superType.isDefinitelyNotNullType) return false

        // i.e subType hasn't not-null supertype, but superType has
        if (hasNotNullSupertype(superType, SupertypesPolicy.UpperIfFlexible)) return false

        // both superType and subType hasn't not-null supertype and are not definitely not null.

        /**
         * If we still don't know, it means, that superType is not classType, for example -- type parameter.
         *
         * For captured types with lower bound this function can give to you false result. Example:
         *  class A<T>, A<in Number> => \exist Q : Number <: Q. A<Q>
         *      isPossibleSubtype(Number, Q) = false.
         *      Such cases should be taken in to account in [NewKotlinTypeChecker.isSubtypeOf] (same for intersection types)
         */

        // classType cannot has special type in supertype list
        if (subType.isClassType) return false

        return hasPathByNotMarkedNullableNodes(subType, superType.constructor)
    }

    private fun TypeCheckerContext.hasNotNullSupertype(type: SimpleType, supertypesPolicy: SupertypesPolicy) =
        anySupertype(type, {
            require(it is SimpleType)
            (it.isClassType && !it.isMarkedNullable) || it.isDefinitelyNotNullType
        }) {
            require(it is SimpleType)
            if (it.isMarkedNullable) SupertypesPolicy.None else supertypesPolicy
        }

    private fun TypeCheckerContext.hasPathByNotMarkedNullableNodes(start: SimpleType, end: TypeConstructor) =
        anySupertype(start, {
            require(it is SimpleType)
            !it.isMarkedNullable && it.constructor == end
        }) {
            require(it is SimpleType)
            if (it.isMarkedNullable) SupertypesPolicy.None else SupertypesPolicy.LowerIfFlexible
        }

}

fun UnwrappedType.hasSupertypeWithGivenTypeConstructor(typeConstructor: TypeConstructor) =
    TypeCheckerContext(false).anySupertype(lowerIfFlexible(), {
        require(it is SimpleType)
        it.constructor == typeConstructor
    }, { SupertypesPolicy.LowerIfFlexible })

fun UnwrappedType.anySuperTypeConstructor(predicate: (TypeConstructor) -> Boolean) =
    TypeCheckerContext(false).anySupertype(lowerIfFlexible(), {
        require(it is SimpleType)
        predicate(it.constructor)
    }, { SupertypesPolicy.LowerIfFlexible })

/**
 * ClassType means that type constructor for this type is type for real class or interface
 */
val SimpleType.isClassType: Boolean get() = constructor.declarationDescriptor is ClassDescriptor

/**
 * SingleClassifierType is one of the following types:
 *  - classType
 *  - type for type parameter
 *  - captured type
 *
 * Such types can contains error types in our arguments, but type constructor isn't errorTypeConstructor
 */
val SimpleType.isSingleClassifierType: Boolean
    get() = !isError &&
            constructor.declarationDescriptor !is TypeAliasDescriptor &&
            (constructor.declarationDescriptor != null || this is CapturedType || this is NewCapturedType || this is DefinitelyNotNullType)

val SimpleType.isIntersectionType: Boolean
    get() = constructor is IntersectionTypeConstructor
