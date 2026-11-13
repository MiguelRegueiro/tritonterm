use strict;
use warnings;

my $path = shift // '/usr/bin/id';
my @stat = stat($path);
if (@stat) {
    my $rgid = $(;
    my $egid = $);
    printf "identity ruid=%d euid=%d rgid=%s egid=%s\n", $<, $>, $rgid, $egid;
    printf "stat result=0 errno=0 mode=%04o uid=%d gid=%d\n", $stat[2] & 07777, $stat[4], $stat[5];
} else {
    printf "stat result=-1 errno=%d error=%s\n", 0 + $!, "$!";
}

sub probe {
    my ($name, $number, @args) = @_;
    $! = 0;
    my $result = syscall($number, @args);
    my $errno = 0 + $!;
    printf "%s result=%d errno=%d error=%s\n", $name, $result, $errno,
        $errno == 0 ? 'success' : "$!";
}

# Linux AArch64 syscall numbers: faccessat=48, faccessat2=439.
probe('sys_faccessat_X_OK', 48, -100, $path, 1);
probe('sys_faccessat2_flags_0', 439, -100, $path, 1, 0);
probe('sys_faccessat2_AT_EACCESS', 439, -100, $path, 1, 512);
