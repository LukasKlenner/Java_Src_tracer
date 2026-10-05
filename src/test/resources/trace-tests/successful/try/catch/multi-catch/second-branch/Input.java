class Input {

    public void m() {
    }

    public static void main(String[] args) {
        try {
            Object a = null;
            a.toString();
        } catch (ArithmeticException e) {
            int y = 1;
        } catch (Exception e) {
            Input obj = new Input();
            obj.m();
        }
    }
}
