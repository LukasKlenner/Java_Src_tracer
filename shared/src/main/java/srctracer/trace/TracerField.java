package srctracer.trace;

public enum TracerField {

    TOTAL_CATCH_COUNT("totalCatchCount", long.class);

    private final String fieldName;

    private final Class<?> fieldType;

    TracerField(String fieldName, Class<?> fieldType) {
        this.fieldName = fieldName;
        this.fieldType = fieldType;
    }

    public String getFieldAccessString() {
        return "srctracer.Trace." + fieldName;
    }

    public String getFieldWriteString(String value) {
        return "srctracer.Trace." + fieldName + " = " + value + ";";
    }

    public String getFieldTypeString() {
        return fieldType.getName();
    }
}
