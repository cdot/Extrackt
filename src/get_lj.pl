#!/usr/bin/perl
# Use get_iplayer to recover .wav for BBC radio programmes
# 0 10 * * * cd /media/disk/Music/Late* && perl ~/workspace/Extrackt/src/get_lj.pl 
use strict;
use FindBin;

my $get_iplayer = "~/Downloads/get_iplayer-2.94/get_iplayer --nocopyright";

my $programme = 'Late Junction';
my @DAYNAMES = qw(Sunday Monday Tuesday Wednesday Thursday Friday Saturday);
my $n = 0;
my %NAME2DAY = map { $_ => $n++ } @DAYNAMES;
my $dayre = join('|', @DAYNAMES);

# For time vars, _t means seconds, _d means days, _s means day name string
my $now_t = time;
my $today_d = (localtime($now_t))[6];

my $prog_fn = $programme;
$prog_fn =~ s/\s+/_/g;

#
# Use get_iplayer to get a list of available programmes.
# The list only gives the day the programme was broadcast.
#
my $report_cmd = "$get_iplayer '$programme' --type radio --info";
print "Getting transmissions with $report_cmd ...\n";
my $report = `$report_cmd`;

#
# Parse the report and build a record for each transmissions
#
my @processes;

my @programmes;
my %cache;
foreach my $line (split(/\n/, $report)) {

    if ($line =~ /^INFO:/) {
        if ($cache{index}) {
            push(@programmes, { %cache });
            %cache = ();
            next;
        }
        die "FUCK $line";
    }

    if ($line =~ /^([A-Za-z]\w*):\s*(.*?)\s*$/) {
        $cache{$1} = $2;
        next;
    }
}
use Data::Dumper;
die Data::Dumper->Dump([\@programmes]);

while (1) {
    my ($id, $recorded_day_s) = ($1, $2);
    # Work out the program date.
    my $recorded_day_d = $NAME2DAY{$recorded_day_s};
    my $days_ago_d = ($recorded_day_d eq $today_d) ?
	7 : ($today_d + 7 - $recorded_day_d) % 7;
    my @ptime = localtime($now_t - $days_ago_d * 24 * 60 * 60);
    my $recorded_date = sprintf(
	'%04d-%02d-%02d', $ptime[5] + 1900, $ptime[4] + 1, $ptime[3]);
    print "Last $recorded_day_s was $days_ago_d days ago, $recorded_date\n";

    my $target = "${prog_fn}_$recorded_date";
    my $flv = "${prog_fn}*${recorded_day_s}*_default.flv";
    my $pid;
    if (-e "$target.wav") {
	print "$target.wav already generated\n";
    } else {
	print "$target.wav not here yet\n";
	my $f = glob($flv);
	if ($f) {
	    print "$f already present\n";
	    $flv = $f;
	} else {
	    # Get the flv using iplayer
	    print "Downloading $id for $target ($flv)\n";
	    next unless shell($get_iplayer,
			      '--quiet',
			      '--force',
			      '--get',
			      '--raw',
			      $id);
	}
	my $f = glob($flv);
	if ($f) {
	    $f =~ /_([^_]+)_default.flv$/;
	    $pid = $1;
	    print "Converting $f to .wav\n";
	    if (shell('avconv', '-v', 'quiet', '-i', $f, "$target.wav")) {
		open(F, '>', "$target.pid") || die $!;
		print F $pid;
		close F;
		unlink $f
	    }
	} else {
	    print STDERR "$flv was not found, could not avconv\n";
	    next;
	}
    }
}

foreach my $d (glob "*.pid") {
    $d =~ /^(.*)\.pid$/;
    my $target = $1;
    # Scrape the web page (no XML available :-( )
    unless (-e "$target.tracks") {
	print "Getting tracks for $target\n";
	my $pid = `cat $target.pid`;
	if (open(F, '>', "$target.tracks")) {
	    print F `perl $FindBin::Bin/scrape.pl $pid`;
	    close F;
	}
    }
}

foreach my $d (glob "*.wav") {
    if ($d =~ /^(.*-\d\d)\.wav$/) {
	my $target = $1;
	unless (-e "$target.silences") {
	    print "Scanning $target for silences\n";
	    shell('java',
		  '-cp', "$FindBin::Bin/../dist/Extrackt.jar",
		  'extrackt.hushfinder.HushFinder',
		  '-o', "$target.silences",
		  "$target.wav");
	}
    }
}

sub shell {
    my $result = system(@_);
    return 1 unless $result;
    print STDERR join(' ', @_)." exited with non-zero $result\n";
    return 0;
}
