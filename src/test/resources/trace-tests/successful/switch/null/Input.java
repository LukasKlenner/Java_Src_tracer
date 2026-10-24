class Input {

    void m() {

    }

    public static void main(String[] args) {
        Input obj = null;
        try {
            switch (obj) {
                default: {
                    obj.m();
                    break;
                }
            }
        } catch (NullPointerException e) {

        }
    }
}
