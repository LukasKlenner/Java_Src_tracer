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
//        A a = new A();
//        B b = new B();
//        C c = new C();
//
//        A[] arr = new A[] {a, b, c};
//
//        int sum = 0;
//        for (int i = 0; i < arr.length; i++) {
//            sum += arr[i].getNumber();
//        }
//        System.out.println("Sum: " + sum);

        A a = new C();

        if (a == null) {
            return;
        }

        a.getNumber();
    }
}