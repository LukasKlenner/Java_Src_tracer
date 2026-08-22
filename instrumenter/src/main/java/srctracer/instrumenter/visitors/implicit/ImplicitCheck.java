package srctracer.instrumenter.visitors.implicit;

import com.github.javaparser.ast.expr.Expression;

public sealed interface ImplicitCheck
        permits NullCheck, ArrayBoundsCheck, DivisionByZeroCheck, NegativeArraySizeCheck, CastCheck {
}

record NullCheck(Expression value) implements ImplicitCheck {
}

record ArrayBoundsCheck(Expression array, Expression index) implements ImplicitCheck {
}

record DivisionByZeroCheck(Expression divisor) implements ImplicitCheck {
}

record NegativeArraySizeCheck(Expression size) implements ImplicitCheck {
}

record CastCheck(Expression value, String targetType) implements ImplicitCheck {
}
