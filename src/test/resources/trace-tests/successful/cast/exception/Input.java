class Input {
    public static void main(String[] args) {
        Object obj = new Object();
        try {
            String s = (String) obj;
        } catch (ClassCastException e) {

        }
    }
}
