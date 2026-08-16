class A {

    public int getNumber() {
        return 1;
    }

}

class B extends A {


    public int getNumber() {
        return 2;
    }

}

class C extends A {
    public int getNumber() {
        return 3;
    }
}

class Inheritance {
    public static void main(String[] args) {
        A a = new A();
        B b = new B();
        C c = new C();

        A[] arr = new A[]{a, b, c};

        int result = 0;

        for (int i = 0; i < arr.length; i++) {
            result += arr[i].getNumber();
        }

        if (result == 6) {
            return;
        } else {
            throw new RuntimeException("Unexpected result: " + result);
        }
    }
}