package org.inferred.freebuilder.processor.util;

import static org.inferred.freebuilder.processor.util.ModelUtils.asElement;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleTypeVisitor6;

/**
 * Handles the byzantine rules of Java scoping.
 */
class ScopeHandler {

  enum ScopeState {
    /** Type is already visible due to scoping rules. */
    IN_SCOPE,
    /** Type is hidden by another type of the same name. */
    HIDDEN,
    /** Type can safely be imported. */
    IMPORTABLE
  }
  enum Visibility { PUBLIC, PROTECTED, PACKAGE, PRIVATE, UNKNOWN }

  private static final String UNIVERSALLY_VISIBLE_PACKAGE = "java.lang";

  private final Elements elements;

  /** Type ↦ visibility in parent scope */
  private final Map<QualifiedName, Visibility> typeVisibility = new HashMap<>();
  /** Scope ↦ simple name ↦ type */
  private final Map<QualifiedName, SetMultimap<String, QualifiedName>> visibleTypes =
      new HashMap<>();
  /** Qualified name as string ↦ qualified name */
  private final Map<String, QualifiedName> generatedTypes = new HashMap<>();

  ScopeHandler(Elements elements) {
    this.elements = elements;
  }

  /**
   * Returns whether {@code type} is visible in, or can be imported into, a compilation unit in
   * {@code pkg}.
   */
  ScopeState visibilityIn(String pkg, QualifiedName type) {
    if (isTopLevelType(pkg, type.getSimpleName())) {
      if (type.isTopLevel() && type.getPackage().equals(pkg)) {
        return ScopeState.IN_SCOPE;
      } else {
        return ScopeState.HIDDEN;
      }
    } else if (!pkg.equals(UNIVERSALLY_VISIBLE_PACKAGE)) {
      return visibilityIn(UNIVERSALLY_VISIBLE_PACKAGE, type);
    } else {
      return ScopeState.IMPORTABLE;
    }
  }

  /**
   * Returns whether {@code type} is visible in, or can be imported into, the body of {@code type}.
   */
  ScopeState visibilityIn(QualifiedName scope, QualifiedName type) {
    Set<QualifiedName> possibleConflicts = typesInScope(scope).get(type.getSimpleName());
    if (possibleConflicts.equals(ImmutableSet.of(type))) {
      return ScopeState.IN_SCOPE;
    } else if (!possibleConflicts.isEmpty()) {
      return ScopeState.HIDDEN;
    } else if (!scope.isTopLevel()) {
      return visibilityIn(scope.enclosingType(), type);
    } else {
      return visibilityIn(scope.getPackage(), type);
    }
  }

  void declareGeneratedType(
      Visibility visibility,
      QualifiedName generatedType,
      Set<String> supertypes) {
    generatedTypes.put(generatedType.toString(), generatedType);
    typeVisibility.put(generatedType, visibility);
    if (!generatedType.isTopLevel()) {
      get(visibleTypes, generatedType.enclosingType())
          .put(generatedType.getSimpleName(), generatedType);
    }
    SetMultimap<String, QualifiedName> visibleInScope = get(visibleTypes, generatedType);
    supertypes.stream().flatMap(this::lookup).forEach(supertype -> {
      for (QualifiedName type : typesInScope(supertype).values()) {
        if (maybeVisibleInScope(generatedType, type)) {
          visibleInScope.put(type.getSimpleName(), type);
        }
      }
    });
  }

  private Stream<QualifiedName> lookup(String typename) {
    if (generatedTypes.containsKey(typename)) {
      return Stream.of(generatedTypes.get(typename));
    }
    TypeElement scopeElement = elements.getTypeElement(typename);
    if (scopeElement != null) {
      return Stream.of(QualifiedName.of(scopeElement));
    }
    return Stream.empty();
  }

  private boolean isTopLevelType(String pkg, String simpleName) {
    String name = pkg + "." + simpleName;
    return generatedTypes.containsKey(name) || elements.getTypeElement(name) != null;
  }

  private static <K1, K2, V> SetMultimap<K2, V> get(Map<K1, SetMultimap<K2, V>> map, K1 key) {
    SetMultimap<K2, V> result = map.get(key);
    if (result == null) {
      result = HashMultimap.create();
      map.put(key, result);
    }
    return result;
  }

  private SetMultimap<String, QualifiedName> typesInScope(QualifiedName scope) {
    SetMultimap<String, QualifiedName> result = visibleTypes.get(scope);
    if (result != null) {
      return result;
    }
    TypeElement scopeElement = elements.getTypeElement(scope.toString());
    return cacheTypesInScope(scope, scopeElement);
  }

  private SetMultimap<String, QualifiedName> cacheTypesInScope(
      QualifiedName scope,
      TypeElement element) {
    SetMultimap<String, QualifiedName> visibleInScope = HashMultimap.create();
    if (element != null) {
      for (QualifiedName type : TYPES_IN_SCOPE.visit(element.getSuperclass(), this)) {
        if (maybeVisibleInScope(scope, type)) {
          visibleInScope.put(type.getSimpleName(), type);
        }
      }
      for (TypeMirror iface : element.getInterfaces()) {
        for (QualifiedName type : TYPES_IN_SCOPE.visit(iface, this)) {
          if (maybeVisibleInScope(scope, type)) {  // In case interfaces ever get private members
            visibleInScope.put(type.getSimpleName(), type);
          }
        }
      }
      for (TypeElement nested : ElementFilter.typesIn(element.getEnclosedElements())) {
        visibleInScope.put(nested.getSimpleName().toString(), QualifiedName.of(nested));
      }
    }
    visibleTypes.put(scope, visibleInScope);
    return visibleInScope;
  }

  private boolean maybeVisibleInScope(QualifiedName scope, QualifiedName type) {
    switch (visibilityOf(type)) {
      case PUBLIC:
      case PROTECTED:
        // type is either nested in scope or a supertype of scope.
        // Either way, it's visible.
        return true;
      case PACKAGE:
        return scope.getPackage().equals(type.getPackage());
      case PRIVATE:
        // Private types are only visible in their enclosing type.
        // Inheriting from the enclosing type is not sufficient.
        return type.enclosingType().equals(scope);
      case UNKNOWN:
        return true;
    }
    throw new IllegalStateException("Unknown visibility " + visibilityOf(type));
  }

  private Visibility visibilityOf(QualifiedName type) {
    Visibility visibility = typeVisibility.get(type);
    if (visibility == null) {
      TypeElement element = elements.getTypeElement(type.toString());
      Set<Modifier> modifiers = element.getModifiers();
      if (modifiers.contains(Modifier.PUBLIC)) {
        visibility = Visibility.PUBLIC;
      } else if (modifiers.contains(Modifier.PROTECTED)) {
        visibility = Visibility.PROTECTED;
      } else if (modifiers.contains(Modifier.PRIVATE)) {
        visibility = Visibility.PRIVATE;
      } else  {
        visibility = Visibility.PACKAGE;
      }
      typeVisibility.put(type, visibility);
    }
    return visibility;
  }

  private static final TypeVisitor<Collection<QualifiedName>, ScopeHandler>
      TYPES_IN_SCOPE =
          new SimpleTypeVisitor6<Collection<QualifiedName>, ScopeHandler>() {
            @Override
            public Collection<QualifiedName> visitDeclared(
                DeclaredType type,
                ScopeHandler scopeHandler) {
              QualifiedName typename = QualifiedName.of(asElement(type));
              SetMultimap<String, QualifiedName> visibleInScope =
                  scopeHandler.visibleTypes.get(typename);
              if (visibleInScope != null) {
                return visibleInScope.values();
              }
              return scopeHandler.cacheTypesInScope(typename, asElement(type)).values();
            }

            @Override
            protected Collection<QualifiedName> defaultAction(TypeMirror e, ScopeHandler p) {
              return ImmutableSet.of();
            }
          };
}
